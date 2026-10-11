
package com.electricitytheft;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class App extends JFrame {

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);

    public App() {
        setTitle("Electricity Theft Detection System");
        setSize(1100, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Sidebar
        JPanel sidebar = new JPanel();
        sidebar.setBackground(new Color(25, 35, 55));
        sidebar.setPreferredSize(new Dimension(220, 700));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(
            BorderFactory.createEmptyBorder(25, 15, 20, 15)
        );

        JLabel logo = new JLabel("POWER GUARD");
        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("Arial", Font.BOLD, 22));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);

        sidebar.add(logo);
        sidebar.add(Box.createVerticalStrut(35));

        // Register application pages
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
                BorderFactory.createEmptyBorder(10, 15, 10, 15)
            );
            button.setHorizontalAlignment(JButton.LEFT);

            button.addActionListener(e -> {
                if ("Dashboard".equals(item)
                        || "Dataset".equals(item)) {
                    cardLayout.show(contentPanel, item);
                } else {
                    javax.swing.JOptionPane.showMessageDialog(
                        this,
                        item + " module is not implemented yet.",
                        item,
                        javax.swing.JOptionPane.INFORMATION_MESSAGE
                    );
                }
            });

            sidebar.add(button);
            sidebar.add(Box.createVerticalStrut(12));
        }

        add(sidebar, BorderLayout.WEST);
        add(contentPanel, BorderLayout.CENTER);
    }

    private JPanel createDashboard() {
        JPanel mainPanel = new JPanel(
            new BorderLayout(20, 25)
        );

        mainPanel.setBackground(new Color(245, 247, 251));
        mainPanel.setBorder(
            BorderFactory.createEmptyBorder(30, 30, 30, 30)
        );

        JLabel heading = new JLabel(
            "Electricity Theft Detection"
        );
        heading.setFont(new Font("Arial", Font.BOLD, 28));
        heading.setForeground(new Color(25, 35, 55));

        JLabel subtitle = new JLabel(
            "Smart meter analytics and non-technical loss monitoring"
        );
        subtitle.setFont(new Font("Arial", Font.PLAIN, 14));
        subtitle.setForeground(Color.GRAY);

        JPanel header = new JPanel();
        header.setLayout(
            new BoxLayout(header, BoxLayout.Y_AXIS)
        );
        header.setOpaque(false);
        header.add(heading);
        header.add(Box.createVerticalStrut(8));
        header.add(subtitle);

        mainPanel.add(header, BorderLayout.NORTH);

        JPanel cards = new JPanel(
            new GridLayout(2, 2, 20, 20)
        );
        cards.setOpaque(false);

        cards.add(createCard(
            "Consumers Analysed", "—", "Awaiting dataset"
        ));
        cards.add(createCard(
            "Potential Theft Cases", "—", "Awaiting detection"
        ));
        cards.add(createCard(
            "Detection Rate", "—", "Awaiting evaluation"
        ));
        cards.add(createCard(
            "Model Status", "Not configured", "ML pipeline pending"
        ));

        mainPanel.add(cards, BorderLayout.CENTER);

        JLabel footer = new JLabel(
            "POWER GUARD | Electricity Theft Analytics"
        );
        footer.setForeground(Color.GRAY);
        mainPanel.add(footer, BorderLayout.SOUTH);

        return mainPanel;
    }

    private JPanel createCard(
            String title, String value, String description) {

        JPanel card = new JPanel();
        card.setLayout(
            new BoxLayout(card, BoxLayout.Y_AXIS)
        );
        card.setBackground(Color.WHITE);
        card.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    new Color(225, 230, 238)
                ),
                BorderFactory.createEmptyBorder(22, 22, 22, 22)
            )
        );

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Arial", Font.PLAIN, 15));
        titleLabel.setForeground(new Color(90, 100, 115));

        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(new Font("Arial", Font.BOLD, 27));
        valueLabel.setForeground(new Color(35, 55, 85));

        JLabel descriptionLabel = new JLabel(description);
        descriptionLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        descriptionLabel.setForeground(Color.GRAY);

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(18));
        card.add(valueLabel);
        card.add(Box.createVerticalStrut(10));
        card.add(descriptionLabel);

        return card;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            App dashboard = new App();
            dashboard.setVisible(true);
        });
    }
}
