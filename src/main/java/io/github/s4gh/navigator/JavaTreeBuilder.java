package io.github.s4gh.navigator;

import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import static javax.lang.model.type.TypeKind.ARRAY;
import static javax.lang.model.type.TypeKind.BOOLEAN;
import static javax.lang.model.type.TypeKind.BYTE;
import static javax.lang.model.type.TypeKind.CHAR;
import static javax.lang.model.type.TypeKind.DECLARED;
import static javax.lang.model.type.TypeKind.DOUBLE;
import static javax.lang.model.type.TypeKind.ERROR;
import static javax.lang.model.type.TypeKind.FLOAT;
import static javax.lang.model.type.TypeKind.INT;
import static javax.lang.model.type.TypeKind.INTERSECTION;
import static javax.lang.model.type.TypeKind.LONG;
import static javax.lang.model.type.TypeKind.SHORT;
import static javax.lang.model.type.TypeKind.TYPEVAR;
import static javax.lang.model.type.TypeKind.UNION;
import static javax.lang.model.type.TypeKind.VOID;
import static javax.lang.model.type.TypeKind.WILDCARD;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;

import org.openide.filesystems.FileObject;

final class JavaTreeBuilder {

    static final class NodeData {
        final String display;
        final ElementHandle<?> handle;
        final List<NodeData> children = new ArrayList<>();
        final Set<Modifier> modifiers;

        NodeData(String display, ElementHandle<?> handle, Set<Modifier> modifiers) {
            this.display = display;
            this.handle = handle;
            this.modifiers = modifiers;
        }
    }

    static final class RootAndHandles {
        private final org.openide.nodes.Node root;
        RootAndHandles(org.openide.nodes.Node root) { this.root = root; }
        org.openide.nodes.Node root() { return root; }
    }

    RootAndHandles buildForFile(FileObject fo, boolean includeInherited, String filter) throws Exception {
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return new RootAndHandles(JavaNodes.errorRoot("Not a Java file"));
        }

        final List<NodeData> topNodes = new ArrayList<>();

        js.runUserActionTask((CompilationController cc) -> {
            cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
            Elements elements = cc.getElements();
            Types types = cc.getTypes();
            Trees trees = cc.getTrees();

            List<? extends TypeElement> topLevelTypes = cc.getTopLevelElements();
            Set<ElementHandle<?>> visited = new HashSet<>();
            
            for (TypeElement type : topLevelTypes) {
                NodeData typeNode = buildTypeNode(cc, type, includeInherited, visited);
                topNodes.add(typeNode);
            }

            // Apply filter
            String q = (filter == null) ? "" : filter.trim().toLowerCase(Locale.ROOT);
            if (!q.isEmpty()) {
                Iterator<NodeData> it = topNodes.iterator();
                while (it.hasNext()) {
                    NodeData n = it.next();
                    if (!keepWithDescendants(n, q)) {
                        it.remove();
                    }
                }
            }

        }, true); // read-only

        if (topNodes.isEmpty()) {
            return new RootAndHandles(JavaNodes.emptyRoot("No matches"));
        }

        org.openide.nodes.Node root = JavaNodes.fromNodeDataList(fo, topNodes, "Classes");
        return new RootAndHandles(root);
    }

    
private NodeData buildTypeNode(CompilationInfo info, TypeElement type, 
        boolean includeInherited, Set<ElementHandle<?>> visited) {
    
        ElementHandle<TypeElement> handle = ElementHandle.create(type);
        if (visited.contains(handle)) {
            return new NodeData(simpleTypeName(type) + " (recursive)", handle, type.getModifiers());
        }
        visited.add(handle);
        
        String typeName = simpleTypeName(type); // e.g. Outer.Inner
        NodeData typeNode = new NodeData(typeName, ElementHandle.create(type), type.getModifiers());


        List<? extends Element> members = includeInherited
                ? info.getElements().getAllMembers(type)
                : type.getEnclosedElements();

        // Safe filtering: only cast if it's truly a TypeElement
        List<TypeElement> innerTypes = members.stream()
                .filter(e -> isTypeKind(e.getKind()))
                .map(e -> (TypeElement) e)
                .filter(te -> !te.equals(type))
                .collect(Collectors.toList());

        List<ExecutableElement> methods = members.stream()
                .filter(e -> e.getKind() == ElementKind.METHOD || e.getKind() == ElementKind.CONSTRUCTOR)
                .map(e -> (ExecutableElement) e)
                .collect(Collectors.toList());

        List<VariableElement> fields = members.stream()
                .filter(e -> e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.ENUM_CONSTANT)
                .map(e -> (VariableElement) e)
                .collect(Collectors.toList());

        // Fields
        for (VariableElement f : fields) {
            if (isSynthetic(f, info)) continue;
            String disp = formatField(f);
            typeNode.children.add(new NodeData(disp, ElementHandle.create(f), f.getModifiers()));
        }

        // Methods
        for (ExecutableElement m : methods) {
            if (isSynthetic(m, info)) continue;
            String disp = formatMethod(m);
            typeNode.children.add(new NodeData(disp, ElementHandle.create(m), m.getModifiers()));
        }

        // Inner classes recursively
        for (TypeElement inner : innerTypes) {
            if (inner.getNestingKind().isNested()) {                
                typeNode.children.add(buildTypeNode(info, inner, includeInherited, visited));

            }
        }

        // Sort with safe resolution
        typeNode.children.sort(Comparator
                .comparing((NodeData n) -> score(n, info))
                .thenComparing(n -> n.display.toLowerCase(Locale.ROOT)));

        return typeNode;
    }

    private static boolean isTypeKind(ElementKind k) {
        return k == ElementKind.CLASS ||
               k == ElementKind.INTERFACE ||
               k == ElementKind.ENUM ||
               k == ElementKind.ANNOTATION_TYPE;
    }

    private static int score(NodeData n, CompilationInfo info) {
        Element el = n.handle.resolve(info);
        if (el == null) return Integer.MAX_VALUE;
        ElementKind k = el.getKind();
        if (isTypeKind(k)) return 0;
        if (k == ElementKind.FIELD || k == ElementKind.ENUM_CONSTANT) return 1;
        return 2;
    }

    private static boolean isSynthetic(Element e, CompilationInfo info) {
        return false;
    }

        private static String formatField(VariableElement f) {
        String type = simpleType(f.asType());
        return f.getSimpleName() + ": " + type;
    }

    private static String formatMethod(ExecutableElement m) {
        String params = m.getParameters().stream()
                .map(p -> simpleType(p.asType())) // types only; add " + \" \" + p.getSimpleName()" if you prefer names too
                .collect(Collectors.joining(", "));
        String ret = simpleType(m.getReturnType());
        Name name = m.getSimpleName();
        if (m.getKind().equals(ElementKind.CONSTRUCTOR)) {
            name = m.getEnclosingElement().getSimpleName();
        }
        return name + "(" + params + "): " + ret;
    }

    /**
     * Returns a short display name for a TypeElement, including nesting
     * (Outer.Inner), but no package.
     */
    private static String simpleTypeName(TypeElement type) {
        String name = type.getSimpleName().toString();
        Element e = type.getEnclosingElement();
        while (e instanceof TypeElement) {
            name = ((TypeElement) e).getSimpleName().toString() + "." + name;
            e = e.getEnclosingElement();
        }
        return name;
    }

    /**
     * Pretty-print a TypeMirror using only simple names, preserving generics,
     * arrays, and wildcards.
     */
    private static String simpleType(TypeMirror tm) {
        switch (tm.getKind()) {
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case CHAR:
            case FLOAT:
            case DOUBLE:
            case VOID:
                return tm.toString(); // primitives & void already short
            case ARRAY:
                ArrayType at = (ArrayType) tm;
                return simpleType(at.getComponentType()) + "[]";
            case DECLARED: {
                DeclaredType dt = (DeclaredType) tm;
                TypeElement te = (TypeElement) dt.asElement();
                StringBuilder sb = new StringBuilder(simpleTypeName(te));

                var args = dt.getTypeArguments();
                if (!args.isEmpty()) {
                    sb.append('<');
                    for (int i = 0; i < args.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(simpleType(args.get(i)));
                    }
                    sb.append('>');
                }
                return sb.toString();
            }
            case TYPEVAR:
            case ERROR:      // unresolved types—fallback to existing ident
                return tm.toString();
            case WILDCARD: {
                WildcardType wt = (WildcardType) tm;
                StringBuilder sb = new StringBuilder("?");
                if (wt.getExtendsBound() != null) {
                    sb.append(" extends ").append(simpleType(wt.getExtendsBound()));
                } else if (wt.getSuperBound() != null) {
                    sb.append(" super ").append(simpleType(wt.getSuperBound()));
                }
                return sb.toString();
            }
            case INTERSECTION:
            case UNION:
            default:
                // Rare in signatures here; fall back to default rendering
                return tm.toString();
        }
    }

    private static boolean keepWithDescendants(NodeData node, String qLower) {
        boolean selfMatches = node.display.toLowerCase(Locale.ROOT).contains(qLower);

        List<NodeData> keptChildren = new ArrayList<>();
        for (NodeData ch : node.children) {
            if (keepWithDescendants(ch, qLower)) {
                keptChildren.add(ch);
            }
        }
        node.children.clear();
        node.children.addAll(keptChildren);

        return selfMatches || !keptChildren.isEmpty();
    }
}