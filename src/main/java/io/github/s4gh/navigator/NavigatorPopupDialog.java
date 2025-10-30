package io.github.s4gh.navigator;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import org.openide.awt.Actions;

public class NavigatorPopupDialog extends JDialog {

    public NavigatorPopupDialog(Frame parent) {
        super(parent, "Filtering Code Navigator", true);
        initComponents();
    }
    
    private JavaMembersPanel javaMembersPanel;

    private void initComponents() {
        // Set a border for some spacing.
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        ClosePopupAction closePopupAction = () -> {
            dispose();
        };
        
        javaMembersPanel = new JavaMembersPanel(closePopupAction); 
        
        content.add(javaMembersPanel, BorderLayout.CENTER);

        // Set the content pane and default behaviors.
        setContentPane(content);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(getParent());
       
        // Optional: handle ESC key to close the dialog.
        getRootPane().registerKeyboardAction(
            e -> closePopupAction.invoke(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT 
        );
    }
    
    public void toggleShowInherited() {
        javaMembersPanel.toggleShowInherited();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension parentDimension = getParent().getSize();
        Dimension halfOfParentDimension = new Dimension((int)Math.ceil(parentDimension.width / 2), (int)Math.ceil(parentDimension.height * 2 / 3));
        return halfOfParentDimension;
    }

    public void installShortcutBridge(JDialog dialog) {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_F,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);

        JRootPane root = dialog.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, "navigator-reinvoke");
        root.getActionMap().put("navigator-reinvoke", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Re-invoke the NB action by ID so behavior stays centralized
                Action a = Actions.forID("Tools", "io.github.s4gh.navigator.OpenNavigatorDialogAction");
                if (a != null) {
                    a.actionPerformed(new ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "shortcut"));
                }
            }
        });
    }
    
   public void focusSearchField() {
        toFront();
        javaMembersPanel.focusSearchField();
    }
}
