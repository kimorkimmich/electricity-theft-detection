
package com.electricitytheft;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class App extends JFrame {

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);

    private static final Path FEATURES_PATH =
            Paths.get("dataset", "sgcc_features.csv");

    private static final String[] FEATURE_NAMES = {
        "MEAN",
        "STD_DEV",
        "MIN",
        "MAX",
        "CV",
        "PAR",
        "TOTAL",
        "TREND",
        "LAG7_AUTOCORR",
        "CONSUMPTION_DEVIATION"
    };

    private JLabel consumersValue;
    private JLabel featuresValue;
    private JLabel datasetValue;
    private JLabel modelValue;

    private JLabel consumersDescription;
    private JLabel featuresDescription;
    private JLabel datasetDescription;

    public App() {

        setTitle("POWER GUARD | Electricity Theft Detection");
        setSize(1100, 700);
        setMinimumSize(new Dimension(900, 600));

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // SIDEBAR
        JPanel sidebar = new JPanel();

        sidebar.setBackground(new Color(25, 35, 55));
        sidebar.setPreferredSize(new Dimension(220, 700));
        sidebar.setLayout(
                new BoxLayout(sidebar, BoxLayout.Y_AXIS));

        sidebar.setBorder(
                BorderFactory.createEmptyBorder(
                        25, 15, 20, 15));

        JLabel logo = new JLabel("POWER GUARD");

        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("Arial", Font.BOLD, 22));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);

        sidebar.add(logo);
        sidebar.add(Box.createVerticalStrut(35));

        // REGISTER PAGES
        contentPanel.add(createDashboard(), "Dashboard");
        contentPanel.add(new DatasetPanel(), "Dataset");

        String[] menuItems = {
            "Dashboard",
            "Dataset",
            "Theft Detection",
            "Consumers",
            "Evaluation",
            "Reports"
        };

        for (String item : menuItems) {

            JButton button = new JButton(item);

            button.setMaximumSize(new Dimension(190, 42));
            button.setAlignmentX(Component.CENTER_ALIGNMENT);

            button.setFocusPainted(false);
            button.setForeground(Color.WHITE);
            button.setBackground(new Color(42, 55, 80));

            button.setBorder(
                    BorderFactory.createEmptyBorder(
                            10, 15, 10, 15));

            button.setHorizontalAlignment(JButton.LEFT);

            button.addActionListener(e -> {

                if ("Dashboard".equals(item)
                        || "Dataset".equals(item)) {

                    cardLayout.show(contentPanel, item);

                } else {

                    JOptionPane.showMessageDialog(
                            this,
                            item + " module is not implemented yet.",
                            item,
                            JOptionPane.INFORMATION_MESSAGE);
                }
            });

            sidebar.add(button);
            sidebar.add(Box.createVerticalStrut(12));
        }

        add(sidebar, BorderLayout.WEST);
        add(contentPanel, BorderLayout.CENTER);

        // LOAD REAL DATA
        loadDatasetStatistics();
    }

    private JPanel createDashboard() {

        JPanel mainPanel =
                new JPanel(new BorderLayout(20, 25));

        mainPanel.setBackground(new Color(245, 247, 251));

        mainPanel.setBorder(
                BorderFactory.createEmptyBorder(
                        30, 30, 30, 30));

        JLabel heading =
                new JLabel("Electricity Theft Detection");

        heading.setFont(
                new Font("Arial", Font.BOLD, 28));

        heading.setForeground(new Color(25, 35, 55));

        JLabel subtitle = new JLabel(
                "Smart meter analytics and non-technical loss monitoring");

        subtitle.setFont(
                new Font("Arial", Font.PLAIN, 14));

        subtitle.setForeground(Color.GRAY);

        JPanel header = new JPanel();

        header.setLayout(
                new BoxLayout(header, BoxLayout.Y_AXIS));

        header.setOpaque(false);
        header.add(heading);
        header.add(Box.createVerticalStrut(8));
        header.add(subtitle);

        mainPanel.add(header, BorderLayout.NORTH);

        JPanel cards =
                new JPanel(new GridLayout(2, 2, 20, 20));

        cards.setOpaque(false);

        // CARD 1
        consumersValue = createValueLabel("Loading...");
        consumersDescription =
                createDescriptionLabel("Reading customer records");

        cards.add(createCard(
                "Consumers Analysed",
                consumersValue,
                consumersDescription));

        // CARD 2
        featuresValue = createValueLabel("Loading...");
        featuresDescription =
                createDescriptionLabel("Checking feature columns");

        cards.add(createCard(
                "Feature Extraction",
                featuresValue,
                featuresDescription));

        // CARD 3
        datasetValue = createValueLabel("Checking...");
        datasetDescription =
                createDescriptionLabel("Checking feature CSV");

        cards.add(createCard(
                "Dataset Status",
                datasetValue,
                datasetDescription));

        // CARD 4
        modelValue = createValueLabel("Not trained");

        cards.add(createCard(
                "Model Status",
                modelValue,
                createDescriptionLabel(
                        "XGBoost + LSTM pipeline pending")));

        mainPanel.add(cards, BorderLayout.CENTER);

        JLabel footer = new JLabel(
                "POWER GUARD | Electricity Theft Analytics");

        footer.setForeground(Color.GRAY);

        mainPanel.add(footer, BorderLayout.SOUTH);

        return mainPanel;
    }

    private JLabel createValueLabel(String value) {

        JLabel label = new JLabel(value);

        label.setFont(
                new Font("Arial", Font.BOLD, 27));

        label.setForeground(new Color(35, 55, 85));

        return label;
    }

    private JLabel createDescriptionLabel(String description) {

        JLabel label = new JLabel(description);

        label.setFont(
                new Font("Arial", Font.PLAIN, 12));

        label.setForeground(Color.GRAY);

        return label;
    }

    private JPanel createCard(
            String title,
            JLabel valueLabel,
            JLabel descriptionLabel) {

        JPanel card = new JPanel();

        card.setLayout(
                new BoxLayout(card, BoxLayout.Y_AXIS));

        card.setBackground(Color.WHITE);

        card.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(
                                new Color(225, 230, 238)),
                        BorderFactory.createEmptyBorder(
                                22, 22, 22, 22)));

        JLabel titleLabel = new JLabel(title);

        titleLabel.setFont(
                new Font("Arial", Font.PLAIN, 15));

        titleLabel.setForeground(
                new Color(90, 100, 115));

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(18));
        card.add(valueLabel);
        card.add(Box.createVerticalStrut(10));
        card.add(descriptionLabel);

        return card;
    }

    // Reads feature CSV without freezing the interface.
    private void loadDatasetStatistics() {

        new SwingWorker<DatasetStats, Void>() {

            @Override
            protected DatasetStats doInBackground()
                    throws Exception {

                if (!Files.exists(FEATURES_PATH)) {
                    throw new IOException(
                            "Feature CSV not found: "
                            + FEATURES_PATH.toAbsolutePath());
                }

                try (
                    Reader reader = Files.newBufferedReader(
                            FEATURES_PATH,
                            StandardCharsets.UTF_8);

                    CSVParser parser =
                            CSVFormat.DEFAULT.builder()
                                    .setHeader()
                                    .setSkipHeaderRecord(true)
                                    .get()
                                    .parse(reader)
                ) {

                    int count = 0;
                    int featureCount = 0;

                    java.util.Map<String, Integer> headers =
                            parser.getHeaderMap();

                    if (!headers.containsKey("CONS_NO")
                            || !headers.containsKey("FLAG")) {
                        throw new IOException(
                                "Required CONS_NO or FLAG column missing.");
                    }

                    for (String feature : FEATURE_NAMES) {
                        if (headers.containsKey(feature)) {
                            featureCount++;
                        }
                    }

                    for (CSVRecord record : parser) {
                        if (record.isConsistent()) {
                            count++;
                        } else {
                            throw new IOException(
                                    "Malformed CSV row: "
                                    + record.getRecordNumber());
                        }
                    }

                    return new DatasetStats(count, featureCount);
                }
            }

            @Override
            protected void done() {

                try {

                    DatasetStats stats = get();

                    consumersValue.setText(
                            String.format("%,d", stats.consumers));

                    consumersDescription.setText(
                            "Customer feature records loaded");

                    featuresValue.setText(
                            stats.featureCount + " / 10");

                    featuresDescription.setText(
                            stats.featureCount == 10
                                    ? "All feature columns present"
                                    : "Feature columns missing");

                    datasetValue.setText("Loaded");

                    datasetDescription.setText(
                            "sgcc_features.csv");

                } catch (Exception ex) {

                    consumersValue.setText("—");
                    featuresValue.setText("—");
                    datasetValue.setText("Unavailable");

                    consumersDescription.setText(
                            "Unable to read customer records");

                    featuresDescription.setText(
                            "Feature validation unavailable");

                    datasetDescription.setText(
                            "Check dataset path and CSV format");

                    System.err.println(
                            "Dashboard dataset error: "
                            + ex.getMessage());
                }
            }

        }.execute();
    }

    private static class DatasetStats {

        final int consumers;
        final int featureCount;

        DatasetStats(int consumers, int featureCount) {
            this.consumers = consumers;
            this.featureCount = featureCount;
        }
    }

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {

            App dashboard = new App();
            dashboard.setVisible(true);

        });
    }
}
