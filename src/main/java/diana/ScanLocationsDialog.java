package diana;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

/**
 * Dialog for editing the folders scanned by {@link ApplicationIndex}.
 */
public class ScanLocationsDialog extends JDialog {
    private final ScanLocations locations;
    private final DefaultListModel<ScanLocations.Location> model = new DefaultListModel<>();
    private final JList<ScanLocations.Location> list = new JList<>(model);
    private final JSpinner depthSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 8, 1));
    private boolean updating;
    private boolean saved;

    public ScanLocationsDialog(JFrame owner, ScanLocations locations) {
        super(owner, "Scan locations", true);
        this.locations = locations;
        setSize(560, 360);
        setLocationRelativeTo(owner);

        for (ScanLocations.Location location : locations.get()) {
            model.addElement(location);
        }

        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.addListSelectionListener(e -> {
            if (updating || e.getValueIsAdjusting()) {
                return;
            }
            int[] indices = list.getSelectedIndices();
            if (indices.length == 1) {
                updating = true;
                depthSpinner.setValue(model.get(indices[0]).depth());
                updating = false;
            }
        });
        depthSpinner.setToolTipText(
                "<html>Selected folders are updated immediately.<br>New folders are added with this depth.</html>");
        depthSpinner.addChangeListener(e -> {
            if (updating) {
                return;
            }
            int[] indices = list.getSelectedIndices();
            if (indices.length == 0) {
                return;
            }
            int depth = (Integer) depthSpinner.getValue();
            updating = true;
            for (int index : indices) {
                ScanLocations.Location location = model.get(index);
                if (location.depth() != depth) {
                    model.setElementAt(new ScanLocations.Location(location.path(), depth), index);
                }
            }
            updating = false;
        });

        JButton add = new JButton("Add folder...");
        add.addActionListener(e -> addFolder());
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> removeSelected());

        JPanel editButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        editButtons.add(add);
        editButtons.add(remove);
        editButtons.add(new JLabel("Depth:"));
        editButtons.add(depthSpinner);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> save());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(cancel);
        actions.add(ok);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(editButtons, BorderLayout.NORTH);
        south.add(actions, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(new JLabel("Folders searched for .exe / .lnk / .bat / .cmd:"), BorderLayout.NORTH);
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
    }

    public boolean isSaved() {
        return saved;
    }

    private void addFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select a folder to scan");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File dir = chooser.getSelectedFile();
        if (dir == null) {
            return;
        }
        String path = dir.toPath().toString();
        for (int i = model.size() - 1; i >= 0; i--) {
            if (model.get(i).path().toString().equalsIgnoreCase(path)) {
                model.remove(i);
            }
        }
        model.addElement(new ScanLocations.Location(dir.toPath(), (Integer) depthSpinner.getValue()));
        list.setSelectedIndex(model.size() - 1);
    }

    private void removeSelected() {
        int[] indices = list.getSelectedIndices();
        for (int i = indices.length - 1; i >= 0; i--) {
            model.remove(indices[i]);
        }
        list.clearSelection();
    }

    private void save() {
        List<ScanLocations.Location> updated = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            updated.add(model.get(i));
        }
        locations.set(updated);
        saved = true;
        dispose();
    }
}
