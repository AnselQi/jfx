/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.tools.fx.monkey.pages;

import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ConstrainedColumnResizeBase;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeTableView.ResizeFeatures;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Callback;
import com.oracle.tools.fx.monkey.util.OptionPane;
import com.oracle.tools.fx.monkey.util.SequenceNumber;
import com.oracle.tools.fx.monkey.util.TestPaneBase;

/**
 * TreeTableView page
 */
public class TreeTableViewPage extends TestPaneBase {
    enum Demo {
        PREF("pref only"),
        VARIABLE("variable cell height"),
        ALL("all set: min, pref, max"),
        EMPTY("empty with pref"),
        MIN_WIDTH("min width"),
        MAX_WIDTH("max width"),
        MIN_WIDTH2("min width (middle)"),
        MAX_WIDTH2("max width (middle)"),
        MIN_WIDTH3("min width (beginning)"),
        MAX_WIDTH3("max width (beginning)"),
        FIXED_MIDDLE("fixed in the middle"),
        ALL_FIXED("all fixed"),
        ALL_MAX("all with maximum width"),
        MIN_IN_CENTER("min widths set in middle columns"),
        MAX_IN_CENTER("max widths set in middle columns"),
        NO_NESTED("no nested columns"),
        NESTED("nested columns"),
        MILLION("million rows"),
        MANY_COLUMNS("many columns"),
        MANY_COLUMNS_SAME("many columns, same pref");

        private final String text;
        Demo(String text) { this.text = text; }
        public String toString() { return text; }
    }

    public enum ResizePolicy {
        AUTO_RESIZE_FLEX_NEXT_COLUMN,
        AUTO_RESIZE_FLEX_LAST_COLUMN,
        AUTO_RESIZE_NEXT_COLUMN,
        AUTO_RESIZE_SUBSEQUENT_COLUMNS,
        AUTO_RESIZE_LAST_COLUMN,
        AUTO_RESIZE_ALL_COLUMNS,
        UNCONSTRAINED_RESIZE_POLICY,
        CONSTRAINED_RESIZE_POLICY,
        USER_DEFINED_EQUAL_WIDTHS,
    }

    public enum Selection {
        SINGLE_ROW("single row selection"),
        MULTIPLE_ROW("multiple row selection"),
        SINGLE_CELL("single cell selection"),
        MULTIPLE_CELL("multiple cell selection"),
        NULL("null selection model");

        private final String text;
        Selection(String text) { this.text = text; }
        public String toString() { return text; }
    }

    public enum Cmd {
        ROWS,
        COL,
        MIN,
        PREF,
        MAX,
        COMBINE,
        COL_WITH_GRAPHIC
    }

    protected final ComboBox<Demo> demoSelector;
    protected final ComboBox<ResizePolicy> policySelector;
    protected final ComboBox<Selection> selectionSelector;
    protected final CheckBox nullFocusModel;
    protected final CheckBox addGraphics;
    protected final CheckBox addSubNodes;
    protected TreeTableView<String> tree;

    public TreeTableViewPage() {
        setId("TreeTableViewPage");

        // selector
        demoSelector = new ComboBox<>();
        demoSelector.setId("demoSelector");
        demoSelector.getItems().addAll(Demo.values());
        demoSelector.setEditable(false);
        demoSelector.getSelectionModel().selectedItemProperty().addListener((s, p, c) -> {
            updatePane();
        });

        policySelector = new ComboBox<>();
        policySelector.setId("policySelector");
        policySelector.getItems().addAll(ResizePolicy.values());
        policySelector.setEditable(false);
        policySelector.getSelectionModel().selectedItemProperty().addListener((s, p, c) -> {
            updatePane();
        });

        selectionSelector = new ComboBox<>();
        selectionSelector.setId("selectionSelector");
        selectionSelector.getItems().addAll(Selection.values());
        selectionSelector.setEditable(false);
        selectionSelector.getSelectionModel().selectedItemProperty().addListener((s, p, c) -> {
            updatePane();
        });

        nullFocusModel = new CheckBox("null focus model");
        nullFocusModel.setId("nullFocusModel");
        nullFocusModel.selectedProperty().addListener((s, p, c) -> {
            updatePane();
        });

        addGraphics = new CheckBox("add graphics");
        addGraphics.setId("addGraphics");
        addGraphics.selectedProperty().addListener((s, p, c) -> {
            updatePane();
        });

        addSubNodes = new CheckBox("add sub-nodes");
        addSubNodes.setId("addSubNodes");
        addSubNodes.selectedProperty().addListener((s, p, c) -> {
            updatePane();
        });

        Button clearButton = new Button("Clear Items");
        clearButton.setOnAction((ev) -> {
            tree.setRoot(new TreeItem(null));
            tree.setShowRoot(false);
        });

        // layout

        OptionPane p = new OptionPane();
        p.label("Data:");
        p.option(demoSelector);
        p.option(clearButton);
        p.label("Column Resize Policy:");
        p.option(policySelector);
        p.label("Selection Model:");
        p.option(selectionSelector);
        p.option(nullFocusModel);
        p.option(addGraphics);
        p.option(addSubNodes);
        setOptions(p);

        demoSelector.getSelectionModel().selectFirst();
        policySelector.getSelectionModel().selectFirst();
        selectionSelector.getSelectionModel().select(Selection.MULTIPLE_CELL);
    }

    protected Callback<ResizeFeatures, Boolean> wrap(Callback<ResizeFeatures, Boolean> policy) {
        return new Callback<ResizeFeatures, Boolean>() {
            @Override
            public Boolean call(ResizeFeatures f) {
                Boolean rv = policy.call(f);
                int ix = f.getTable().getColumns().indexOf(f.getColumn());
                System.out.println(
                    "col=" + (ix < 0 ? f.getColumn() : ix) +
                    " delta=" + f.getDelta() +
                    " w=" + f.getTable().getWidth() +
                    " rv=" + rv
                );
                return rv;
            }
        };
    }

    protected String describe(TreeTableColumn c) {
        StringBuilder sb = new StringBuilder();
        if (c.getMinWidth() != 10.0) {
            sb.append("m");
        }
        if (c.getPrefWidth() != 80.0) {
            sb.append("p");
        }
        if (c.getMaxWidth() != 5000.0) {
            sb.append("X");
        }
        return sb.toString();
    }

    protected Callback<ResizeFeatures, Boolean> createPolicy(ResizePolicy p) {
        switch (p) {
        case AUTO_RESIZE_FLEX_NEXT_COLUMN:
            return TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_NEXT_COLUMN;
        case AUTO_RESIZE_FLEX_LAST_COLUMN:
            return TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN;
        case AUTO_RESIZE_ALL_COLUMNS:
            return TreeTableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS;
        case AUTO_RESIZE_LAST_COLUMN:
            return TreeTableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN;
        case AUTO_RESIZE_NEXT_COLUMN:
            return TreeTableView.CONSTRAINED_RESIZE_POLICY_NEXT_COLUMN;
        case AUTO_RESIZE_SUBSEQUENT_COLUMNS:
            return TreeTableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS;
        case CONSTRAINED_RESIZE_POLICY:
            return TreeTableView.CONSTRAINED_RESIZE_POLICY;
        case UNCONSTRAINED_RESIZE_POLICY:
            return TreeTableView.UNCONSTRAINED_RESIZE_POLICY;
        case USER_DEFINED_EQUAL_WIDTHS:
            return new UserDefinedResizePolicy();
        default:
            throw new Error("?" + p);
        }
    }

    protected Object[] createSpec(Demo d) {
        switch (d) {
        case ALL:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL,
                Cmd.COL, Cmd.MIN, 20, Cmd.PREF, 20, Cmd.MAX, 20,
                Cmd.COL, Cmd.PREF, 200,
                Cmd.COL, Cmd.PREF, 300, Cmd.MAX, 400,
                Cmd.COL
            };
        case PREF:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL, Cmd.PREF, 100,
                Cmd.COL, Cmd.PREF, 200,
                Cmd.COL, Cmd.PREF, 300,
                Cmd.COL, Cmd.PREF, 400
            };
        case VARIABLE:
            return new Object[] {
                Cmd.ROWS, 10_000,
                Cmd.COL_WITH_GRAPHIC,
                Cmd.COL_WITH_GRAPHIC,
                Cmd.COL_WITH_GRAPHIC
            };
        case EMPTY:
            return new Object[] {
                Cmd.COL, Cmd.PREF, 100,
                Cmd.COL, Cmd.PREF, 200,
                Cmd.COL, Cmd.PREF, 300
            };
        case MIN_WIDTH:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL, Cmd.MIN, 300
            };
        case MAX_WIDTH:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL, Cmd.MAX, 100
            };
        case MIN_WIDTH2:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL, Cmd.MIN, 300,
                Cmd.COL
            };
        case MAX_WIDTH2:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL, Cmd.MAX, 100,
                Cmd.COL
            };
        case MIN_WIDTH3:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL, Cmd.MIN, 300,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL
            };
        case MAX_WIDTH3:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL, Cmd.MAX, 100,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL
            };
        case MIN_IN_CENTER:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL,
                Cmd.COL, Cmd.MIN, 20,
                Cmd.COL, Cmd.MIN, 30,
                Cmd.COL, Cmd.MIN, 40,
                Cmd.COL, Cmd.MIN, 50,
                Cmd.COL, Cmd.MIN, 60,
                Cmd.COL
            };
        case MAX_IN_CENTER:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL,
                Cmd.COL, Cmd.MAX, 20,
                Cmd.COL, Cmd.MAX, 30,
                Cmd.COL, Cmd.MAX, 40,
                Cmd.COL, Cmd.MAX, 50,
                Cmd.COL, Cmd.MAX, 60,
                Cmd.COL
            };
        case FIXED_MIDDLE:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL, Cmd.MIN, 100, Cmd.MAX, 100,
                Cmd.COL, Cmd.MIN, 100, Cmd.MAX, 100,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL
            };
        case ALL_FIXED:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL, Cmd.MIN, 50, Cmd.MAX, 50,
                Cmd.COL, Cmd.MIN, 50, Cmd.MAX, 50,
                Cmd.COL, Cmd.MIN, 50, Cmd.MAX, 50
            };
        case ALL_MAX:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL, Cmd.MAX, 50,
                Cmd.COL, Cmd.MAX, 50,
                Cmd.COL, Cmd.MAX, 50
            };
       case NO_NESTED:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL, Cmd.PREF, 100,
                Cmd.COL, Cmd.PREF, 200,
                Cmd.COL, Cmd.PREF, 300,
                Cmd.COL, Cmd.MIN, 100, Cmd.MAX, 100,
                Cmd.COL, Cmd.PREF, 100,
                Cmd.COL, Cmd.MIN, 100,
                Cmd.COL, Cmd.MAX, 100,
                Cmd.COL, Cmd.PREF, 400,
                Cmd.COL
            };
        case NESTED:
            return new Object[] {
                Cmd.ROWS, 3,
                Cmd.COL, Cmd.PREF, 100,
                Cmd.COL, Cmd.PREF, 200,
                Cmd.COL, Cmd.PREF, 300,
                Cmd.COL, Cmd.MIN, 100, Cmd.MAX, 100,
                Cmd.COL, Cmd.PREF, 100,
                Cmd.COL, Cmd.MIN, 100,
                Cmd.COL, Cmd.MAX, 100,
                Cmd.COL, Cmd.PREF, 400,
                Cmd.COL,
                Cmd.COMBINE, 0, 3,
                Cmd.COMBINE, 1, 2
            };
        case MANY_COLUMNS:
            return new Object[] {
                Cmd.ROWS, 300,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL,
                Cmd.COL
            };
        case MANY_COLUMNS_SAME:
            return new Object[] {
                Cmd.ROWS, 300,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30,
                Cmd.COL, Cmd.PREF, 30
            };
        case MILLION:
            return new Object[] {
                Cmd.ROWS, 1_000_000,
                Cmd.COL,
                Cmd.COL
            };
        default:
            throw new Error("?" + d);
        }
    }

    protected void updatePane() {
        Demo d = demoSelector.getSelectionModel().getSelectedItem();
        ResizePolicy p = policySelector.getSelectionModel().getSelectedItem();
        Object[] spec = createSpec(d);

        Pane n = createPane(d, p, spec);
        setContent(n);
    }

    protected void combineColumns(TreeTableView<String> t, int ix, int count, int name) {
        TreeTableColumn<String, ?> tc = new TreeTableColumn<>();
        tc.setText("N" + name);

        for (int i = 0; i < count; i++) {
            TreeTableColumn<String, ?> c = t.getColumns().remove(ix);
            tc.getColumns().add(c);
        }
        t.getColumns().add(ix, tc);
    }

    protected Pane createPane(Demo demo, ResizePolicy policy, Object[] spec) {
        if ((demo == null) || (spec == null) || (policy == null)) {
            return new BorderPane();
        }

        boolean cellSelection = false;
        boolean nullSelectionModel = false;
        SelectionMode selectionMode = SelectionMode.SINGLE;
        Selection sel = selectionSelector.getSelectionModel().getSelectedItem();
        if (sel != null) {
            switch (sel) {
            case MULTIPLE_CELL:
                selectionMode = SelectionMode.MULTIPLE;
                cellSelection = true;
                break;
            case MULTIPLE_ROW:
                selectionMode = SelectionMode.MULTIPLE;
                break;
            case NULL:
                nullSelectionModel = true;
                break;
            case SINGLE_CELL:
                cellSelection = true;
                break;
            case SINGLE_ROW:
                break;
            default:
                throw new Error("?" + sel);
            }
        }

        tree = new TreeTableView<>(new TreeItem<>(null));
        tree.getSelectionModel().setCellSelectionEnabled(cellSelection);
        tree.getSelectionModel().setSelectionMode(selectionMode);
        if (nullSelectionModel) {
            tree.setSelectionModel(null);
        }
        if (nullFocusModel.isSelected()) {
            tree.setFocusModel(null);
        }

        Callback<ResizeFeatures, Boolean> p = createPolicy(policy);
        tree.setColumnResizePolicy(p);

        TreeTableColumn<String, String> lastColumn = null;
        int id = 1;

        for (int i = 0; i < spec.length;) {
            Object x = spec[i++];
            if (x instanceof Cmd cmd) {
                switch (cmd) {
                case COL:
                    {
                        TreeTableColumn<String,String> c = new TreeTableColumn<>();
                        tree.getColumns().add(c);
                        c.setText("C" + tree.getColumns().size());
                        c.setCellValueFactory((f) -> new SimpleStringProperty(describe(c)));
                        lastColumn = c;
                    }
                    break;
                case COL_WITH_GRAPHIC:
                    {
                        TreeTableColumn<String,String> c = new TreeTableColumn<>();
                        tree.getColumns().add(c);
                        c.setText("C" + tree.getColumns().size());
                        c.setCellValueFactory((f) -> new SimpleStringProperty(describe(c)));
                        c.setCellFactory((r) -> {
                            return new TreeTableCell<>() {
                                @Override
                                protected void updateItem(String item, boolean empty) {
                                    super.updateItem(item, empty);
                                    Text t = new Text("11111111111111111111111111111111111111111111111111111111111111111111111111111111111111\n2\n3\n");
                                    t.wrappingWidthProperty().bind(widthProperty());
                                    setPrefHeight(USE_COMPUTED_SIZE);
                                    setGraphic(t);
                                }
                            };
                        });
                        lastColumn = c;
                    }
                    break;
                case MAX:
                    {
                        int w = (int)(spec[i++]);
                        lastColumn.setMaxWidth(w);
                    }
                    break;
                case MIN:
                    {
                        int w = (int)(spec[i++]);
                        lastColumn.setMinWidth(w);
                    }
                    break;
                case PREF:
                    {
                        int w = (int)(spec[i++]);
                        lastColumn.setPrefWidth(w);
                    }
                    break;
                case ROWS:
                    {
                        int n = (int)(spec[i++]);
                        TreeItem subNodeTreeItem = null;
                        for (int j = 0; j < n; j++) {
                            TreeItem treeItem = new TreeItem(newItem());
                            if (addSubNodes.isSelected()) {
                                subNodeTreeItem = new TreeItem(newItem());
                                treeItem.getChildren().add(subNodeTreeItem);
                            }
                            if (addGraphics.isSelected()) {
                                treeItem.setGraphic(new Rectangle(10, 10, Color.RED));
                                if (subNodeTreeItem != null) {
                                    subNodeTreeItem.setGraphic(new Rectangle(10, 10));
                                }
                            }
                            tree.getRoot().getChildren().add(treeItem);
                        }
                    }
                    break;
                case COMBINE:
                    int ix = (int)(spec[i++]);
                    int ct = (int)(spec[i++]);
                    combineColumns(tree, ix, ct, id++);
                    break;
                default:
                    throw new Error("?" + cmd);
                }
            } else {
                throw new Error("?" + x);
            }
        }

        BorderPane bp = new BorderPane();
        bp.setCenter(tree);
        return bp;
    }

    protected String newItem() {
        return SequenceNumber.next();
    }

    /**
     * a user-defined policy demonstrates that we can indeed create a custom policy using the new API.
     * this policy simply sizes all columns equally.
     */
    protected static class UserDefinedResizePolicy
        extends ConstrainedColumnResizeBase
        implements Callback<TreeTableView.ResizeFeatures, Boolean> {

        @SuppressWarnings("unchecked")
        @Override
        public Boolean call(ResizeFeatures rf) {
            List<? extends TableColumnBase<?, ?>> visibleLeafColumns = rf.getTable().getVisibleLeafColumns();
            int sz = visibleLeafColumns.size();
            // using added public method getContentWidth()
            double w = rf.getContentWidth() / sz;
            for (TableColumnBase<?, ?> c: visibleLeafColumns) {
                // using added public method setColumnWidth()
                rf.setColumnWidth(c, w);
            }
            return false;
        }
    }
}
