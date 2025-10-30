package io.github.s4gh.navigator;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.BorderFactory;

import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.java.source.JavaSource;
import org.openide.explorer.ExplorerManager;
import org.openide.explorer.view.OutlineView;
import org.openide.filesystems.FileObject;
import org.openide.util.RequestProcessor;
import org.netbeans.modules.editor.NbEditorUtilities;
import org.openide.nodes.Node;


import java.awt.event.KeyEvent;
import java.beans.PropertyVetoException;
import java.util.Locale;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;


public class JavaMembersPanel extends JPanel implements ExplorerManager.Provider {

    private final JTextField searchField = new JTextField();
    private volatile boolean includeInherited = false;
    private final JCheckBox inheritedCheck = new JCheckBox("Show inherited (Ctrl+Alt+F)", includeInherited);
    private final ExplorerManager explorer = new ExplorerManager();
    private final OutlineView outline = new OutlineView("Members");
    private final RequestProcessor RP = new RequestProcessor(JavaMembersPanel.class);
    private final AtomicReference<FileObject> currentFile = new AtomicReference<>();

    private volatile String currentFilter = "";

    private final JavaTreeBuilder builder = new JavaTreeBuilder();

    private final ClosePopupAction closePopupAction;
    
    public JavaMembersPanel(ClosePopupAction closePopupAction) {
        super(new BorderLayout());
        this.closePopupAction = closePopupAction;
        initUI();
        hookEditorChanges();
        rebuildModelDebounced();
    }

    private void initUI() {
        // Top bar with search + checkbox
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0)); // 4px padding

        JPanel left = new JPanel(new BorderLayout());
        searchField.putClientProperty("JTextField.placeholderText", "Search…");
        left.add(searchField, BorderLayout.CENTER);
        top.add(left, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        right.add(inheritedCheck);
        top.add(right, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);

        // Tree table
        outline.getOutline().setRootVisible(false);
        outline.getOutline().setTableHeader(null);
        outline.setColumnHeaderView(null);
        outline.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        outline.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        outline.getOutline().setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        outline.getOutline().setFillsViewportHeight(true);

        // One wide column (tree column)
        outline.setPropertyColumns(); // just the tree
        add(outline, BorderLayout.CENTER);

        // Events
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onFilterChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onFilterChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onFilterChanged(); }
        });
        
        searchField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "jump-first-match");
        searchField.getActionMap().put("jump-first-match", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                jumpToFirstMatch();
            }
        });

        inheritedCheck.addActionListener(e -> {
            includeInherited = inheritedCheck.isSelected();
            rebuildModelDebounced();
        });
        
        JavaNodes.closePopupAction = this.closePopupAction;

        // Enter / double-click opens element (OutlineView already does, but we ensure it)
        InputMap im = outline.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = outline.getActionMap();
        im.put(KeyStroke.getKeyStroke("ENTER"), "open-selected");
        am.put("open-selected", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                JavaNodes.invokePreferredAction(explorer.getSelectedNodes());
            }
        });
    }

    private void onFilterChanged() {
        currentFilter = searchField.getText() == null ? "" : searchField.getText().trim();
        rebuildModelDebounced();
    }

    private void hookEditorChanges() {
        EditorRegistry.addPropertyChangeListener(evt -> {
            switch (evt.getPropertyName()) {
                case EditorRegistry.FOCUS_GAINED_PROPERTY:
                case EditorRegistry.LAST_FOCUSED_REMOVED_PROPERTY:
                case EditorRegistry.FOCUSED_DOCUMENT_PROPERTY:
                    rebuildModelDebounced();
                    break;
            }
        });
    }

    private void rebuildModelDebounced() {
        RP.post(this::rebuildModel, 150); // small debounce for typing and editor switches
    }

    private void rebuildModel() {
        SwingUtilities.invokeLater(() -> {
            FileObject fo = findActiveJavaFile();
            currentFile.set(fo);
            if (fo == null || JavaSource.forFileObject(fo) == null) {
                explorer.setRootContext(JavaNodes.emptyRoot("No Java file focused"));
                return;
            }
            explorer.setRootContext(JavaNodes.loadingRoot("Loading…"));
        });

        FileObject fo = findActiveJavaFile();
        if (fo == null || JavaSource.forFileObject(fo) == null) {
            return;
        }

        try {
            JavaTreeBuilder.RootAndHandles result = builder.buildForFile(
                    fo,
                    includeInherited,
                    currentFilter);

            // If filter produced matches, keep enclosing classes; builder handles that.
            SwingUtilities.invokeLater(() -> {
                var root = result.root();
                explorer.setRootContext(root);
                expandAllAsync();
            });

        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> explorer.setRootContext(
                    JavaNodes.errorRoot("Error: " + ex.getMessage())));
        }
    }

    private FileObject findActiveJavaFile() {
        javax.swing.text.JTextComponent comp = EditorRegistry.lastFocusedComponent();
        if (comp == null) return null;
        javax.swing.text.Document doc = comp.getDocument();
        if (doc == null) return null;
        return NbEditorUtilities.getFileObject(doc);
    }

    @Override
    public ExplorerManager getExplorerManager() {
        return explorer;
    }
    
    private void expandAllAsync() {
        SwingUtilities.invokeLater(() -> {
            Node root = explorer.getRootContext();
            if (root != null) {
                expandRecursively(root);
            }
        });
    }

    private void expandRecursively(Node n) {
        // expand current node
        outline.expandNode(n);  // OutlineView convenience method
        // recurse
        for (Node ch : n.getChildren().getNodes(true)) {
            expandRecursively(ch);
        }
    }
    
    /**
     * Jump to the first node whose display name contains the current filter.
     */
    private void jumpToFirstMatch() {
        final String q = (searchField.getText() == null ? "" : searchField.getText().trim())
                .toLowerCase(Locale.ROOT);

        Node root = explorer.getRootContext();
        if (root == null) {
            return;
        }

        // Prefer an actual "match" over ancestor placeholders
        Node match = findFirstMatch(root, q);

        // If there is no textual match (e.g., empty query), fall back to first visible node
        if (match == null) {
            Node[] top = root.getChildren().getNodes(true);
            match = top.length > 0 ? top[0] : root;
        }

        selectAndReveal(match);
    }

    /**
     * Preorder traversal in view order; returns first node whose display name
     * contains q.
     */
    private Node findFirstMatch(Node node, String q) {
        if (node == null) {
            return null;
        }
        String dn = node.getDisplayName();
        if (!q.isEmpty() && dn != null && dn.toLowerCase(Locale.ROOT).contains(q)) {
            return node;
        }
        for (Node ch : node.getChildren().getNodes(true)) {
            Node n = findFirstMatch(ch, q);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    /**
     * Select the node via ExplorerManager and ensure the Outline scrolls to it.
     */
    private void selectAndReveal(Node n) {
        if (n == null) {
            return;
        }
        try {
            explorer.setSelectedNodes(new Node[]{n});
        } catch (PropertyVetoException ignore) {
        }

        // Move focus into the outline and scroll to the selected row
        SwingUtilities.invokeLater(() -> {
            var out = outline.getOutline();
            out.requestFocusInWindow();

            // Walk rows and find the first that maps to our Node (robust even if sorting/filters are used)
            for (int viewRow = 0; viewRow < out.getRowCount(); viewRow++) {
                int modelRow = out.convertRowIndexToModel(viewRow);
                org.netbeans.swing.etable.ETable.RowMapping mapping
                        = new org.netbeans.swing.etable.ETable.RowMapping(modelRow, out.getModel(), out);
                Object cell = mapping.getTransformedValue(0);
                if (cell instanceof Node && cell == n) {
                    out.getSelectionModel().setSelectionInterval(viewRow, viewRow);
                    out.scrollRectToVisible(out.getCellRect(viewRow, 0, true));
                    break;
                }
            }
        });
    }
    
    public void toggleShowInherited() {
        boolean isSelected = inheritedCheck.isSelected();
        boolean newSelectdValue = !isSelected;
        inheritedCheck.setSelected(newSelectdValue);
        includeInherited = newSelectdValue;
        rebuildModel();
    }
    
    public void focusSearchField() {
        SwingUtilities.invokeLater(() -> {
            searchField.requestFocusInWindow();
            searchField.setCaretPosition(searchField.getText().length());
        });
    }


}