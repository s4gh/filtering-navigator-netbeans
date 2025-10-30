package io.github.s4gh.navigator;

import java.awt.event.ActionEvent;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.Action;

import java.io.IOException;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import org.netbeans.api.java.source.ui.ElementOpen;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;

import org.openide.filesystems.FileObject;

import org.openide.nodes.AbstractNode;
import org.openide.nodes.ChildFactory;
import org.openide.nodes.Children;
import org.openide.nodes.Node;

final class JavaNodes {
    
    protected static ClosePopupAction closePopupAction;

    private JavaNodes() {}

    static Node emptyRoot(String msg) {
        AbstractNode n = new AbstractNode(Children.LEAF);
        n.setDisplayName(msg);
        return n;
    }

    static Node loadingRoot(String msg) {
        AbstractNode n = new AbstractNode(Children.LEAF);
        n.setDisplayName(msg);
        return n;
    }

    static Node errorRoot(String msg) {
        AbstractNode n = new AbstractNode(Children.LEAF);
        n.setDisplayName(msg);
        return n;
    }

    static Node fromNodeDataList(FileObject fo, List<JavaTreeBuilder.NodeData> nodes, String rootName) {
        return new RootNode(fo, nodes, rootName);
    }

    private static final class RootNode extends AbstractNode {
        RootNode(FileObject fo, List<JavaTreeBuilder.NodeData> list, String name) {
//            super(Children.create(new NodeChildrenFactory(fo, list), true));
            super((list == null || list.isEmpty())
                    ? Children.LEAF
                    : Children.create(new NodeChildrenFactory(fo, list), false));

            setDisplayName(name);
        }
    }

    private static final class NodeChildrenFactory extends ChildFactory<JavaTreeBuilder.NodeData> {
        private final FileObject fo;
        private final List<JavaTreeBuilder.NodeData> data;
        NodeChildrenFactory(FileObject fo, List<JavaTreeBuilder.NodeData> data) {
            this.fo = fo;
            this.data = data;
        }
        @Override protected boolean createKeys(List<JavaTreeBuilder.NodeData> toPopulate) {
            toPopulate.addAll(data);
            return true;
        }
        @Override protected Node createNodeForKey(JavaTreeBuilder.NodeData key) {
            return new ElementNode(fo, key);
        }
    }

    static final class ElementNode extends AbstractNode {

        private final FileObject file;
        private final JavaTreeBuilder.NodeData data;

        ElementNode(FileObject fo, JavaTreeBuilder.NodeData data) {
            //super(Children.create(new NodeChildrenFactory(fo, data.children), true));
            super((data.children == null || data.children.isEmpty())
                    ? Children.LEAF
                    : Children.create(new NodeChildrenFactory(fo, data.children), false));
            
            this.file = fo;
            this.data = data;

            setDisplayName(data.display);
            setIconBaseWithExtension(iconFor(data.handle.getKind(), data.modifiers));
        }

        @Override
        public Action getPreferredAction() {
            return new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    openElement();                    
                    if (closePopupAction != null) {
                        closePopupAction.invoke();
                    }

                }
            };
        }

        @Override
        public Action[] getActions(boolean context) {
            return new Action[] { getPreferredAction() };
        }

        private void openElement() {
            JavaSource js = JavaSource.forFileObject(file);
            if (js == null) {
                return;
            }

            try {
                js.runUserActionTask(cc -> {
                    try {
                        cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                        Element el = data.handle.resolve(cc);
                        if (el == null) {
                            return;
                        }

                        Element enclosing = el.getEnclosingElement();
                        if (enclosing instanceof TypeElement) {
                            FileObject declaringFile = SourceUtils.getFile(ElementHandle.create((TypeElement) enclosing), cc.getClasspathInfo());
                            if (declaringFile != null) {
                                ElementOpen.open(declaringFile, ElementHandle.create(el));
                            }
                        }
                    } catch (IOException ex) {
                        // Handle IOException from SourceUtils.getFile or ElementOpen.open
                        ex.printStackTrace(); // or use Logger to log the error
                    }
                }, true);
            } catch (IOException ex) {
                // Handle IOException from runUserActionTask
                ex.printStackTrace(); // or use Logger to log the error
            }
        }
    }

    private static String iconFor(ElementKind kind, Set<Modifier> modifiers) {
        String additionalQulifiers = getStaticQualifier(modifiers) + getVisibilityQualifier(modifiers);
        return switch (kind) {
            case RECORD        -> "icons/record.svg";
            case CLASS         -> "icons/class.svg";
            case INTERFACE     -> "icons/interface.svg";
            case ENUM          -> "icons/enum.svg";
            case ANNOTATION_TYPE -> "icons/class.svg";
            case METHOD        -> "icons/method" + additionalQulifiers + ".svg";
            case CONSTRUCTOR   -> "icons/constructor"+ additionalQulifiers + ".svg";
            case FIELD, ENUM_CONSTANT -> "icons/field" + additionalQulifiers + ".svg";
            default            -> "icons/defaultNode.svg";
        };
    }
    
    private static String getStaticQualifier(Set<Modifier> modifiers){
        if (modifiers.contains(Modifier.STATIC)) {
            return "Static";
        }
        return "";
    }
    
    private static String getVisibilityQualifier(Set<Modifier> modifiers){
        if (modifiers.contains(Modifier.PUBLIC)) {
            return "Public";
        } else if (modifiers.contains(Modifier.PROTECTED)) {
            return "Protected";
        } else if (modifiers.contains(Modifier.PRIVATE)) {
            return "Private";
        }
        return "";
    }

    /** Trigger preferred action on selected nodes (Enter key). */
    static void invokePreferredAction(Node[] nodes) {
        if (nodes == null) return;
        for (Node n : nodes) {
            Action a = n.getPreferredAction();
            if (a != null && a.isEnabled()) {
                a.actionPerformed(new ActionEvent(n, ActionEvent.ACTION_PERFORMED, "open"));
            }
        }
    }
}