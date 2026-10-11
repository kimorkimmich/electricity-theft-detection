
package com.electricitytheft;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

public class DatasetPanel extends JPanel {

    private final JLabel statusLabel =
            new JLabel("No dataset loaded");

    private final JLabel rowsLabel =
            new JLabel("Rows: —");

    private final JLabel columnsLabel =
            new JLabel("Columns: —");

    private final JLabel missingLabel =
            new JLabel("Missing values: —");

    private final JLabel labelInfo =
            new JLabel("Consumer ID / target: checking...");

    private final JLabel columnInfo =
            new JLabel("Column overview: —");

    private final DefaultTableModel tableModel =
            new DefaultTableModel();

    private final JTable table = new JTable(tableModel);

    public DatasetPanel() {
        setLayout(new BorderLayout(12, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        setBackground(new Color(245, 247, 251));

        // Page heading
        JLabel heading = new JLabel("Dataset Explorer");
        heading.setFont(new Font("Arial", Font.BOLD, 24));
        heading.setForeground(new Color(25, 35, 55));

        JButton loadButton = new JButton("Load CSV");
        loadButton.addActionListener(e -> chooseFile());

        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setOpaque(false);
        titlePanel.add(heading, BorderLayout.WEST);
        titlePanel.add(loadButton, BorderLayout.EAST);

        // Dataset statistics
        JPanel statistics = new JPanel(
                new GridLayout(2, 3, 12, 10));

        statistics.setOpaque(false);

        statistics.add(rowsLabel);
        statistics.add(columnsLabel);
        statistics.add(missingLabel);
        statistics.add(labelInfo);
        statistics.add(columnInfo);
        statistics.add(new JLabel("CSV inspection"));

        JPanel header = new JPanel(
                new BorderLayout(0, 15));

        header.setOpaque(false);
        header.add(titlePanel, BorderLayout.NORTH);
        header.add(statistics, BorderLayout.SOUTH);

        // Preview table
        table.setFont(new Font("Arial", Font.PLAIN, 12));
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);

        JScrollPane scrollPane = new JScrollPane(table);

        statusLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        statusLabel.setForeground(new Color(70, 80, 95));

        add(header, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        // Automatically attempt to load the default CSV.
        Path defaultFile = Paths.get("data", "SGCC.csv");

        if (Files.isRegularFile(defaultFile)) {
            loadFile(defaultFile);
        } else {
            statusLabel.setText(
                    "Select your CSV using the Load CSV button."
            );
        }
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select electricity dataset CSV");

        chooser.setFileFilter(
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "CSV files (*.csv)", "csv"));

        if (chooser.showOpenDialog(this)
                == JFileChooser.APPROVE_OPTION) {
            loadFile(chooser.getSelectedFile().toPath());
        }
    }

    private void loadFile(Path file) {
        long totalRows = 0;
        long missingCells = 0;
        int previewRows = 0;

        try (BufferedReader reader = Files.newBufferedReader(
                file, StandardCharsets.UTF_8)) {

            String headerLine = reader.readLine();

            if (headerLine == null) {
                throw new IOException(
                        "The selected CSV file is empty.");
            }

            String[] headers = parseCsvLine(headerLine);

            if (headers.length == 0) {
                throw new IOException(
                        "Could not identify the CSV columns.");
            }

            // Clear old data and configure the table.
            tableModel.setRowCount(0);
            tableModel.setColumnCount(0);

            for (String header : headers) {
                tableModel.addColumn(header);
            }

            // Identify important columns.
            String consumerColumn = "Not found";
            String targetColumn = "Not found";

            for (String header : headers) {
                String normalized = header.trim()
                        .replace("\uFEFF", "")
                        .toUpperCase(Locale.ROOT);

                if (normalized.equals("CONS_NO")) {
                    consumerColumn = header;
                }

                if (normalized.equals("FLAG")) {
                    targetColumn = header;
                }
            }

            // Read all records for statistics, but display
            // only the first 20 records in the preview table.
            String line;

            while ((line = reader.readLine()) != null) {
                totalRows++;

                String[] values = parseCsvLine(line);

                for (int i = 0; i < headers.length; i++) {
                    if (i >= values.length
                            || values[i].trim().isEmpty()) {
                        missingCells++;
                    }
                }

                if (previewRows < 20) {
                    Object[] row = new Object[headers.length];

                    for (int i = 0; i < headers.length; i++) {
                        row[i] = i < values.length
                                ? values[i] : "";
                    }

                    tableModel.addRow(row);
                    previewRows++;
                }
            }

            // Display statistics.
            rowsLabel.setText("Data rows: " + totalRows);
            columnsLabel.setText("Columns: " + headers.length);

            long totalCells = totalRows * headers.length;

            double missingPercentage = totalCells == 0
                    ? 0.0
                    : 100.0 * missingCells / totalCells;

            missingLabel.setText(String.format(
                    Locale.ROOT,
                    "Missing: %d (%.2f%%)",
                    missingCells,
                    missingPercentage
            ));

            labelInfo.setText(
                    "Consumer ID: " + consumerColumn
                    + " | Target: " + targetColumn
            );

            int count = Math.min(5, headers.length);

            String firstColumns = String.join(
                    ", ",
                    Arrays.copyOfRange(headers, 0, count)
            );

            String lastColumns = String.join(
                    ", ",
                    Arrays.copyOfRange(
                            headers,
                            Math.max(0, headers.length - count),
                            headers.length
                    )
            );

            columnInfo.setText(
                    "First: " + firstColumns
                    + " | Last: " + lastColumns
            );

            statusLabel.setText(
                    "Loaded: " + file.toAbsolutePath()
                    + " | Preview: " + previewRows + " rows"
            );

        } catch (IOException ex) {
            statusLabel.setText("Dataset loading failed.");

            JOptionPane.showMessageDialog(
                    this,
                    "Could not load CSV:\n" + ex.getMessage(),
                    "Dataset Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    // Handles commas and escaped quotes within quoted CSV fields.
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                if (inQuotes
                        && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {

                    field.append('"');
                    i++;

                } else {
                    inQuotes = !inQuotes;
                }

            } else if (ch == ',' && !inQuotes) {
                fields.add(field.toString());
                field.setLength(0);

            } else {
                field.append(ch);
            }
        }

        fields.add(field.toString());

        return fields.toArray(String[]::new);
    }
}
