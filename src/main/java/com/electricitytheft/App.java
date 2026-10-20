package com.electricitytheft;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/** Electricity Theft Detection System: UI and dataset exploration, not a trained detector. */
public class App extends JFrame {
    private static final Color NAVY = new Color(22, 34, 57), BLUE = new Color(43, 107, 205);
    private static final Color BG = new Color(245, 248, 252), MUTED = new Color(93, 105, 124);
    private static final String[] FEATURES = {"MEAN","STD_DEV","MIN","MAX","CV","PAR","TOTAL","TREND","LAG7_AUTOCORR","CONSUMPTION_DEVIATION"};
    private static final String[] PAGES = {"Dataset Management","Feature Extraction","Theft Detection","Consumer Analysis","Alerts","Model Evaluation","Dashboard","Reports"};
    private final CardLayout cards = new CardLayout();
    private final JPanel screens = new JPanel(cards);
    private final Map<String, JButton> menu = new LinkedHashMap<>();
    private final JLabel status = new JLabel("No dataset loaded");
    private final JLabel fileLabel = new JLabel("No dataset selected");
    private final JLabel datasetSummary = new JLabel("Upload raw SGCC CSV to clean and extract features.");
    private final JTextArea cleaningReport = new JTextArea("No cleaning run yet.");
    private DataCleaner.CleaningResult lastCleaning;
    private final JLabel qualityLabel = new JLabel("No quality audit yet");
    private final DefaultTableModel datasetModel = model("Consumer ID", "Label", "Missing features", "Invalid values", "Status");
    private final DefaultTableModel featuresModel = model("Feature", "Present", "Missing cells", "Mean value");
    private final DefaultTableModel consumerModel = model("Consumer ID", "Known dataset label", "Feature count", "Missing values");
    private final DefaultTableModel alertsModel = model("Consumer ID", "Category", "Details", "Status");
    private final DefaultTableModel detectionModel = model("Consumer ID", "Tier 1 score", "Tier 2 result", "Case status");
    private final JLabel[] qualityValues = new JLabel[5];
    private final String[] qualityNames = {"Missing readings found","Invalid readings found","Duplicate rows removed","Empty rows removed","Outliers capped"};
    private final MiniBars qualityBars = new MiniBars(qualityNames, new double[5], BLUE);
    private final MiniBars featureBars = new MiniBars(FEATURES, new double[FEATURES.length], BLUE);
    private final JLabel detectionInfo = new JLabel("No model predictions loaded. Dataset labels are not predictions.");
    private final JLabel evaluationInfo = new JLabel("Evaluation unavailable: provide held-out predictions and ground-truth labels.");
    private final JLabel overview = new JLabel("No analysis has been run.");
    private final JLabel consumerDetail = new JLabel("Choose a consumer to inspect their source feature values.");
    private final JTextArea featureDetail = new JTextArea();
    private final JTable consumersTable = table(consumerModel);
    private final JTable datasetTable = table(datasetModel);
    private final JTable alertsTable = table(alertsModel);
    private final JTable detectionTable = table(detectionModel);
    private final List<Consumer> consumers = new ArrayList<>();
    private final List<Alert> alerts = new ArrayList<>();
    private Path sourceFile;
    private String[] headers = new String[0];
    private long[] qualityCounts = new long[5];
    private boolean predictionsImported = false;
    private long truePositive, falsePositive, trueNegative, falseNegative;

    public App() {
        super("Electricity Theft Detection System");
        setSize(1250, 790);
        setMinimumSize(new Dimension(980, 630));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        screens.setBackground(BG);
        registerPages();
        add(sidebar(), BorderLayout.WEST);
        add(screens, BorderLayout.CENTER);
        status.setBorder(new EmptyBorder(8, 16, 8, 16));
        add(status, BorderLayout.SOUTH);
        showPage(PAGES[0]);
        // Raw SGCC files are processed only after explicit upload.
        // This prevents previously generated feature CSVs from masquerading as raw input.
    }
    private void registerPages() {
        screens.add(datasetPage(), PAGES[0]);
        screens.add(featurePage(), PAGES[1]);
        screens.add(detectionPage(), PAGES[2]);
        screens.add(consumerPage(), PAGES[3]);
        screens.add(alertsPage(), PAGES[4]);
        screens.add(evaluationPage(), PAGES[5]);
        screens.add(dashboardPage(), PAGES[6]);
        screens.add(reportsPage(), PAGES[7]);
    }
    private JPanel sidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(NAVY);
        sidebar.setPreferredSize(new Dimension(228, 0));
        sidebar.setBorder(new EmptyBorder(25, 12, 15, 12));
        JLabel brand = new JLabel("ELECTRICITY THEFT");
        brand.setForeground(Color.WHITE); brand.setFont(new Font("SansSerif", Font.BOLD, 17));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(brand);
        JLabel sub = new JLabel("Detection System");
        sub.setForeground(new Color(177, 194, 218)); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(sub);
        sidebar.add(Box.createVerticalStrut(30));
        for (String name : PAGES) {
            JButton b = new JButton(name);
            b.setFont(new Font("SansSerif", Font.PLAIN, 13));
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 41));
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setBorder(new EmptyBorder(10, 13, 10, 8));
            b.setFocusPainted(false); b.setOpaque(true); b.setForeground(Color.WHITE);
            b.addActionListener(e -> showPage(name));
            menu.put(name, b); sidebar.add(b); sidebar.add(Box.createVerticalStrut(7));
        }
        sidebar.add(Box.createVerticalGlue());
        return sidebar;
    }
    private void showPage(String name) {
        cards.show(screens, name);
        for (Map.Entry<String,JButton> e : menu.entrySet())
            e.getValue().setBackground(e.getKey().equals(name) ? BLUE : new Color(39, 54, 78));
    }
    private JPanel page(String title, String subtitle) {
        JPanel p = new JPanel(new BorderLayout(0, 15));
        p.setBackground(BG); p.setBorder(new EmptyBorder(24, 25, 22, 25));
        JPanel header = new JPanel(); header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel t = new JLabel(title); t.setFont(new Font("SansSerif", Font.BOLD, 26)); t.setForeground(NAVY);
        JLabel st = new JLabel(subtitle); st.setForeground(MUTED);
        header.add(t); header.add(Box.createVerticalStrut(5)); header.add(st);
        p.add(header, BorderLayout.NORTH); return p;
    }
    private JPanel panel() {
        JPanel p = new JPanel(new BorderLayout(8, 10)); p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(222,229,239)), new EmptyBorder(16,16,16,16)));
        return p;
    }
    private static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }
    private static JTable table(DefaultTableModel m) {
        JTable t = new JTable(m); t.setRowHeight(27); t.setAutoCreateRowSorter(true);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setReorderingAllowed(false); return t;
    }
    private static JScrollPane scroll(JTable table) { return new JScrollPane(table); }
    private static JButton button(String title, ActionListener action) {
        JButton b = new JButton(title); b.setBackground(BLUE); b.setForeground(Color.WHITE);
        b.setFocusPainted(false); b.addActionListener(action); return b;
    }
    private static JLabel section(String text) { JLabel label = new JLabel(text); label.setFont(new Font("SansSerif", Font.BOLD, 16)); return label; }
    private JPanel datasetPage() {
        JPanel p = page("Dataset Management", "Upload raw SGCC readings, automatically clean them, and generate a complete report.");
        JPanel body = new JPanel(new BorderLayout(12,12)); body.setOpaque(false);
        JPanel top = panel();
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT,10,3)); controls.setOpaque(false);
        controls.add(button("Upload Raw SGCC CSV + Clean", e -> chooseCsv())); controls.add(fileLabel);
        top.add(controls, BorderLayout.NORTH);
        top.add(datasetSummary, BorderLayout.CENTER);
        body.add(top, BorderLayout.NORTH);
        JPanel split = new JPanel(new GridLayout(1,2,12,12)); split.setOpaque(false);
        JPanel audit = panel(); audit.add(section("Five data-quality indicators"), BorderLayout.NORTH);
        JPanel quality = new JPanel(new BorderLayout(6,10)); quality.setOpaque(false);
        JPanel metrics = new JPanel(new GridLayout(5,1,3,3)); metrics.setOpaque(false);
        for (int i=0;i<5;i++) { qualityValues[i] = new JLabel(qualityNames[i] + ": —"); metrics.add(qualityValues[i]); }
        quality.add(metrics, BorderLayout.NORTH); quality.add(qualityBars, BorderLayout.CENTER);
        audit.add(quality,BorderLayout.CENTER); audit.add(qualityLabel, BorderLayout.SOUTH);
        JPanel preview = panel(); preview.add(section("Consumer preview"), BorderLayout.NORTH);
        preview.add(scroll(datasetTable),BorderLayout.CENTER);
        split.add(audit);split.add(preview);body.add(split,BorderLayout.CENTER);
        p.add(body,BorderLayout.CENTER);
        JPanel bottom = panel();
        bottom.add(section("Cleaning report — actual values from uploaded data"),BorderLayout.NORTH);
        cleaningReport.setEditable(false);
        cleaningReport.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        cleaningReport.setRows(6);
        bottom.add(new JScrollPane(cleaningReport),BorderLayout.CENTER);
        JPanel exports = new JPanel(new FlowLayout(FlowLayout.LEFT));exports.setOpaque(false);
        exports.add(button("Open cleaning report",e -> openProducedFile(lastCleaning == null ? null : lastCleaning.report)));
        exports.add(button("Open cleaned CSV",e -> openProducedFile(lastCleaning == null ? null : lastCleaning.cleaned)));
        exports.add(button("Open change log",e -> openProducedFile(lastCleaning == null ? null : lastCleaning.log)));
        bottom.add(exports,BorderLayout.SOUTH);
        body.add(bottom,BorderLayout.SOUTH);
        return p;
    }
    private JPanel featurePage() {
        JPanel p = page("Feature Extraction", "Explore the ten feature columns used by the Tier-1 XGBoost screener.");
        JPanel left = panel();left.add(section("Extracted features — dataset averages"), BorderLayout.NORTH);
        left.add(featureBars,BorderLayout.CENTER);
        JLabel note = new JLabel("Values shown are raw feature averages; axes are independent across feature units.");
        note.setForeground(MUTED);left.add(note,BorderLayout.SOUTH);
        JPanel right = panel();right.add(section("Feature completeness"),BorderLayout.NORTH);
        right.add(scroll(table(featuresModel)),BorderLayout.CENTER);
        JPanel split = new JPanel(new GridLayout(1,2,12,12));split.setOpaque(false);
        split.add(left);split.add(right);p.add(split,BorderLayout.CENTER);return p;
    }
    private JPanel detectionPage() {
        JPanel p = page("Theft Detection", "XGBoost screening → Top-K selection → LSTM temporal refinement.");
        JPanel top = panel();
        top.add(section("Model pipeline"),BorderLayout.NORTH);
        top.add(new JLabel("Tier 1: XGBoost  →  Top-K suspicious consumers  →  Tier 2: LSTM  →  Review queue"),BorderLayout.CENTER);
        top.add(detectionInfo,BorderLayout.SOUTH);
        JPanel grid = panel();grid.add(section("Predicted cases (not verified theft)"),BorderLayout.NORTH);
        grid.add(scroll(detectionTable),BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));bottom.setOpaque(false);
        bottom.add(button("Import prediction CSV", e -> choosePredictions()));
        bottom.add(new JLabel("Columns: CONS_NO, TIER1_SCORE, TIER2_SCORE, PREDICTION, optionally TRUE_LABEL"));
        grid.add(bottom,BorderLayout.SOUTH);
        JPanel body = new JPanel(new BorderLayout(12,12));body.setOpaque(false);
        body.add(top,BorderLayout.NORTH);body.add(grid,BorderLayout.CENTER);p.add(body,BorderLayout.CENTER);
        detectionTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount()==2 && detectionTable.getSelectedRow()>=0) {
                    String id = detectionTable.getValueAt(detectionTable.getSelectedRow(),0).toString();
                    selectConsumer(id);showPage("Consumer Analysis");
                }
            }
        });
        return p;
    }
    private JPanel consumerPage() {
        JPanel p = page("Consumer Analysis", "Select or double-click an individual consumer to inspect their data.");
        JPanel split = new JPanel(new GridLayout(1,2,12,12));split.setOpaque(false);
        JPanel left = panel();left.add(section("Consumers (dataset labels, not predictions)"),BorderLayout.NORTH);
        left.add(scroll(consumersTable),BorderLayout.CENTER);
        JPanel right = panel();right.add(consumerDetail,BorderLayout.NORTH);
        featureDetail.setEditable(false);featureDetail.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
        right.add(new JScrollPane(featureDetail),BorderLayout.CENTER);
        split.add(left);split.add(right);p.add(split,BorderLayout.CENTER);
        consumersTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && consumersTable.getSelectedRow()>=0) {
                String id=consumersTable.getValueAt(consumersTable.getSelectedRow(),0).toString();showConsumer(id);
            }
        });
        return p;
    }
    private JPanel alertsPage() {
        JPanel p=page("Alerts", "Data-quality alerts and investigation queue. Model alerts require imported predictions.");
        JPanel card=panel();card.add(section("Alerts"),BorderLayout.NORTH);
        card.add(scroll(alertsTable),BorderLayout.CENTER);
        JPanel bottom=new JPanel(new FlowLayout(FlowLayout.LEFT));bottom.setOpaque(false);
        bottom.add(button("Acknowledge selected",e->changeAlert("Acknowledged")));
        bottom.add(button("Under review",e->changeAlert("Under Review")));
        bottom.add(button("Resolved",e->changeAlert("Resolved")));
        card.add(bottom,BorderLayout.SOUTH);p.add(card,BorderLayout.CENTER);return p;
    }
    private JPanel evaluationPage() {
        JPanel p=page("Model Evaluation", "Scores must come from held-out ground-truth labels and real predictions.");
        JPanel card=panel();card.add(section("Evaluation summary"),BorderLayout.NORTH);
        JTextArea explanation=new JTextArea("Metrics: accuracy, precision, recall, F1, false-positive rate, and confusion matrix.\n"+
                "Additional model integration: PR-AUC, ROC-AUC, Precision@K, Recall@K and runtime.\n\n"+
                "No scores are invented. Import model output with TRUE_LABEL to calculate binary classification metrics.");
        explanation.setEditable(false);explanation.setOpaque(false);explanation.setLineWrap(true);explanation.setWrapStyleWord(true);
        JPanel center=new JPanel(new BorderLayout(10,15));center.setOpaque(false);
        center.add(evaluationInfo,BorderLayout.NORTH);center.add(explanation,BorderLayout.CENTER);
        card.add(center,BorderLayout.CENTER);p.add(card,BorderLayout.CENTER);return p;
    }
    private JPanel dashboardPage() {
        JPanel p=page("Dashboard", "Summary of imported data and completed analysis runs.");
        JPanel card=panel();card.add(section("Analysis overview"),BorderLayout.NORTH);card.add(overview,BorderLayout.CENTER);
        JLabel label=new JLabel("Dataset counts, model predictions and confirmed inspections must remain distinct.");
        label.setForeground(MUTED);card.add(label,BorderLayout.SOUTH);p.add(card,BorderLayout.CENTER);return p;
    }
    private JPanel reportsPage() {
        JPanel p=page("Reports", "Export the data actually loaded into the application.");
        JPanel card=panel();card.add(section("Available exports"),BorderLayout.NORTH);
        JPanel buttons=new JPanel(new GridLayout(4,1,12,12));buttons.setOpaque(false);
        buttons.add(button("Export dataset quality summary (CSV)",e->exportQuality()));
        buttons.add(button("Export consumer feature records (CSV)",e->exportConsumers()));
        buttons.add(button("Export alert queue (CSV)",e->exportAlerts()));
        buttons.add(button("Export predicted case list (CSV)",e->exportPredictions()));
        card.add(buttons,BorderLayout.CENTER);
        card.add(new JLabel("PDF investigation reports can be added after the reporting library and model evidence are integrated."),BorderLayout.SOUTH);
        p.add(card,BorderLayout.CENTER);return p;
    }
    private void chooseCsv() {
        JFileChooser chooser=new JFileChooser();
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)loadAsync(chooser.getSelectedFile().toPath());
    }
    private static final class WorkflowResult {
        DataCleaner.CleaningResult cleaning;
        Path features;
        LoadResult dataset;
    }
    private void loadAsync(Path path) {
        status.setText("Cleaning raw SGCC: " + path.getFileName() + " ... (may take a while)");
        new SwingWorker<WorkflowResult,Void>() {
            @Override protected WorkflowResult doInBackground() throws Exception {
                WorkflowResult result = new WorkflowResult();
                Path dir = Paths.get("dataset");
                result.cleaning = DataCleaner.clean(path, dir);
                result.features = FeatureExtractor.extract(result.cleaning.cleaned, dir.resolve("sgcc_features.csv"));
                result.dataset = parseDataset(result.features);
                return result;
            }
            @Override protected void done() {
                try {
                    WorkflowResult result = get();
                    applyDataset(result.features, result.dataset);
                    showCleaningResults(result.cleaning);
                    status.setText("Cleaning and feature extraction complete — " + result.cleaning.retainedRows + " consumers retained.");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();status.setText("Processing interrupted.");
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause=ex.getCause();status.setText("Cleaning failed.");
                    JOptionPane.showMessageDialog(App.this,cause!=null?cause.getMessage():ex.getMessage(),
                        "SGCC processing error",JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
    private void showCleaningResults(DataCleaner.CleaningResult c) {
        lastCleaning = c;
        qualityCounts = new long[]{c.missingFound,c.invalidFound,c.removedDuplicates,c.removedEmpty,c.outliersCapped};
        double[] bars = new double[qualityCounts.length];
        for(int i=0;i<bars.length;i++){
            bars[i] = qualityCounts[i];
            qualityValues[i].setText(qualityNames[i] + ": " + String.format("%,d",qualityCounts[i]));
        }
        qualityBars.setValues(bars);
        qualityLabel.setText("Raw SGCC counts (not missing feature columns).");
        fileLabel.setText(c.input.getFileName().toString());
        datasetSummary.setText(String.format("Input %,d | Retained %,d | Removed %,d | Feature columns: 10/10",
            c.inputRows,c.retainedRows,c.inputRows-c.retainedRows));
        cleaningReport.setText(
            c.summary() + "\n" +
            "Rows removed — empty: " + c.removedEmpty + ", duplicates: " + c.removedDuplicates +
            ", invalid metadata: " + c.removedMetadata + "\n" +
            "Missing repaired: " + c.missingFilled + " | Invalid repaired: " + c.invalidFilled +
            " | Outliers capped: " + c.outliersCapped + "\n" +
            "Normal retained: " + c.normalRows + " | Theft-labelled retained: " + c.fraudRows + "\n" +
            "Cleaned CSV: " + c.cleaned + "\n" +
            "Detailed changes: " + c.log + "\n" +
            "Full report: " + c.report);
        cleaningReport.setCaretPosition(0);
    }
    private void openProducedFile(Path path) {
        if(path==null||!Files.isRegularFile(path)){
            JOptionPane.showMessageDialog(this,"Run cleaning first.");return;
        }
        try {Desktop.getDesktop().open(path.toFile());}
        catch (IOException | UnsupportedOperationException ex) {
            JOptionPane.showMessageDialog(this,"File location: " + path.toAbsolutePath());
        }
    }
    private static class Consumer {
        String id,label; LinkedHashMap<String,String> values=new LinkedHashMap<>();
        int missing,invalid; Consumer(String id,String label){this.id=id;this.label=label;}
    }
    private static class Alert {
        String id,kind,details,status="New";
        Alert(String id,String kind,String details){this.id=id;this.kind=kind;this.details=details;}
    }
    private static class LoadResult {
        List<Consumer> consumers = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        long[] quality=new long[5],missingByFeature=new long[FEATURES.length],validByFeature=new long[FEATURES.length];
        double[] sums=new double[FEATURES.length];
        String[] headers;
    }
    private LoadResult parseDataset(Path path) throws IOException {
        LoadResult result=new LoadResult();Set<String> seen=new HashSet<>();
        try(Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8);
            CSVParser parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            Map<String,Integer> col=parser.getHeaderMap();
            if(!col.containsKey("CONS_NO")||!col.containsKey("FLAG"))
                throw new IOException("CSV must have CONS_NO and FLAG headers.");
            result.headers=col.keySet().toArray(new String[0]);
            for(CSVRecord row:parser) {
                if(!row.isConsistent())throw new IOException("Malformed row " + row.getRecordNumber());
                String id=row.get("CONS_NO").trim(),flag=row.get("FLAG").trim();
                Consumer c=new Consumer(id,flag);
                if(!seen.add(id)){result.quality[2]++;result.alerts.add(new Alert(id,"Duplicate ID","Repeated consumer identifier"));}
                if(!flag.equals("0")&&!flag.equals("1")){result.quality[4]++;result.alerts.add(new Alert(id,"Invalid label","FLAG must be 0 or 1"));}
                for(int i=0;i<FEATURES.length;i++){
                    String feature=FEATURES[i];String raw=col.containsKey(feature)?row.get(feature).trim():"";
                    c.values.put(feature,raw);
                    if(raw.isBlank()||raw.equalsIgnoreCase("nan")||raw.equalsIgnoreCase("null")){
                        c.missing++;result.quality[0]++;result.missingByFeature[i]++;
                    }else{
                        try{double v=Double.parseDouble(raw);if(!Double.isFinite(v))throw new NumberFormatException();
                            result.sums[i]+=v;result.validByFeature[i]++;
                        }catch(NumberFormatException ex){c.invalid++;result.quality[1]++;}
                    }
                }
                if(c.missing+c.invalid>0){result.quality[3]++;
                    result.alerts.add(new Alert(id,"Data quality",c.missing+" missing / "+c.invalid+" invalid feature cells"));}
                result.consumers.add(c);
            }
        }
        return result;
    }
    private void applyDataset(Path path,LoadResult result) {
        sourceFile=path;headers=result.headers;consumers.clear();consumers.addAll(result.consumers);
        alerts.clear();alerts.addAll(result.alerts);qualityCounts=result.quality;
        predictionsImported=false;detectionModel.setRowCount(0);detectionInfo.setText("No model predictions loaded. Dataset labels are not predictions.");
        evaluationInfo.setText("Evaluation unavailable: provide held-out predictions and ground-truth labels.");
        datasetModel.setRowCount(0);consumerModel.setRowCount(0);featuresModel.setRowCount(0);
        for(int i=0;i<Math.min(250,consumers.size());i++){
            Consumer c=consumers.get(i);
            datasetModel.addRow(new Object[]{c.id,c.label,c.missing,c.invalid,c.missing+c.invalid==0?"Complete":"Review"});
        }
        for(Consumer c:consumers) consumerModel.addRow(new Object[]{c.id,c.label,FEATURES.length-c.missing-c.invalid,c.missing});
        double[] means=new double[FEATURES.length];
        for(int i=0;i<FEATURES.length;i++){
            boolean present=Arrays.asList(headers).contains(FEATURES[i]);
            means[i]=result.validByFeature[i]>0?result.sums[i]/result.validByFeature[i]:0;
            featuresModel.addRow(new Object[]{FEATURES[i],present?"Yes":"No",result.missingByFeature[i],result.validByFeature[i]>0?String.format(Locale.US,"%.4f",means[i]):"—"});
        }
        featureBars.setValues(means);
        double max=1;for(long v:qualityCounts)max=Math.max(max,v);
        double[] q=new double[qualityCounts.length];for(int i=0;i<q.length;i++){q[i]=qualityCounts[i];qualityValues[i].setText(qualityNames[i]+": "+String.format("%,d",qualityCounts[i]));}
        qualityBars.setValues(q);
        qualityLabel.setText("Feature CSV loaded; see raw cleaning report for original data quality.");
        fileLabel.setText(path.getFileName().toString());
        datasetSummary.setText(String.format("%,d consumer rows  |  %d of 10 feature headers present",consumers.size(),countFeatureHeaders()));
        status.setText("Loaded: "+path.toAbsolutePath());
        refreshAlerts();refreshOverview();consumerDetail.setText("Choose a consumer");featureDetail.setText("");
    }
    private int countFeatureHeaders(){int n=0;for(String f:FEATURES)if(Arrays.asList(headers).contains(f))n++;return n;}
    private void showConsumer(String id){
        for(Consumer c:consumers) if(c.id.equals(id)){
            consumerDetail.setText("Consumer: "+c.id+" | Dataset FLAG: "+c.label);
            StringBuilder s=new StringBuilder("Dataset label 0 = normal, 1 = theft (source data only)\n\n");
            for(Map.Entry<String,String> entry:c.values.entrySet())s.append(String.format("%-23s %s%n",entry.getKey(),entry.getValue().isEmpty()?"(missing)":entry.getValue()));
            s.append("\nMissing feature cells: ").append(c.missing).append("\nInvalid cells: ").append(c.invalid);
            featureDetail.setText(s.toString());featureDetail.setCaretPosition(0);return;
        }
        consumerDetail.setText("No consumer found: "+id);featureDetail.setText("");
    }
    private void selectConsumer(String id){
        for(int i=0;i<consumersTable.getRowCount();i++)if(consumersTable.getValueAt(i,0).equals(id)){
            consumersTable.setRowSelectionInterval(i,i);consumersTable.scrollRectToVisible(consumersTable.getCellRect(i,0,true));break;
        }
        showConsumer(id);
    }
    private void refreshAlerts(){alertsModel.setRowCount(0);for(Alert a:alerts)alertsModel.addRow(new Object[]{a.id,a.kind,a.details,a.status});}
    private void changeAlert(String state){int view=alertsTable.getSelectedRow();if(view<0)return;
        int index=alertsTable.convertRowIndexToModel(view);alerts.get(index).status=state;refreshAlerts();refreshOverview();}
    private void refreshOverview(){
        int suspected=0;for(int i=0;i<detectionModel.getRowCount();i++)if("Flagged".equals(detectionModel.getValueAt(i,3)))suspected++;
        long unresolved=alerts.stream().filter(a->!a.status.equals("Resolved")).count();
        overview.setText("Consumers loaded: "+String.format("%,d",consumers.size())+"  |  Predicted flags: "+
                (predictionsImported?suspected:"not available")+"  |  Unresolved alerts: "+unresolved+
                "  |  Model status: "+(predictionsImported?"Predictions imported":"Not connected"));
    }
    private void choosePredictions(){JFileChooser ch=new JFileChooser();if(ch.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        try{importPredictions(ch.getSelectedFile().toPath());}catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Prediction import error",JOptionPane.ERROR_MESSAGE);}}
    private void importPredictions(Path path)throws IOException{
        DefaultTableModel tmp=model("Consumer ID","Tier 1 score","Tier 2 result","Case status");
        List<Alert> modelAlerts=new ArrayList<>();long tp=0,fp=0,tn=0,fn=0;int labelled=0,flagged=0,records=0;
        try(Reader r=Files.newBufferedReader(path,StandardCharsets.UTF_8);
            CSVParser parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(r)){
            Set<String> h=parser.getHeaderMap().keySet();
            for(String required:new String[]{"CONS_NO","TIER1_SCORE","TIER2_SCORE","PREDICTION"})
                if(!h.contains(required))throw new IOException("Missing prediction CSV column: "+required);
            for(CSVRecord row:parser){
                String id=row.get("CONS_NO").trim(),pred=row.get("PREDICTION").trim();
                if(!pred.equals("0")&&!pred.equals("1"))throw new IOException("PREDICTION must be 0 or 1 at row "+row.getRecordNumber());
                double t1=Double.parseDouble(row.get("TIER1_SCORE")),t2=Double.parseDouble(row.get("TIER2_SCORE"));
                if(!Double.isFinite(t1)||!Double.isFinite(t2)||t1<0||t1>1||t2<0||t2>1)throw new IOException("Scores must be in [0,1]");
                boolean yes=pred.equals("1");records++;if(yes){flagged++;modelAlerts.add(new Alert(id,"Model review","Tier 2 predicted suspicious; score "+String.format(Locale.US,"%.3f",t2)));}
                tmp.addRow(new Object[]{id,String.format(Locale.US,"%.3f",t1),String.format(Locale.US,"%.3f",t2),yes?"Flagged":"Not flagged"});
                if(h.contains("TRUE_LABEL")&&!row.get("TRUE_LABEL").trim().isEmpty()){
                    String truth=row.get("TRUE_LABEL").trim();if(!truth.equals("0")&&!truth.equals("1"))throw new IOException("TRUE_LABEL must be 0 or 1");
                    labelled++;if(yes&&truth.equals("1"))tp++;else if(yes)fp++;else if(truth.equals("1"))fn++;else tn++;
                }
            }
        }catch(NumberFormatException ex){throw new IOException("Invalid numeric prediction score",ex);}
        detectionModel.setRowCount(0);
        for(int i=0;i<tmp.getRowCount();i++)detectionModel.addRow(new Object[]{tmp.getValueAt(i,0),tmp.getValueAt(i,1),tmp.getValueAt(i,2),tmp.getValueAt(i,3)});
        alerts.removeIf(a->a.kind.equals("Model review"));alerts.addAll(modelAlerts);refreshAlerts();
        predictionsImported=true;truePositive=tp;falsePositive=fp;trueNegative=tn;falseNegative=fn;
        detectionInfo.setText(records+" predictions imported; "+flagged+" flagged for review (not confirmed theft).");
        if(labelled>0){
            double precision=ratio(tp,tp+fp),recall=ratio(tp,tp+fn),accuracy=ratio(tp+tn,tp+tn+fp+fn);
            double f1=precision+recall>0?2*precision*recall/(precision+recall):0;
            double fpr=ratio(fp,fp+tn);
            evaluationInfo.setText(String.format(Locale.US,"Held-out labels: %d | Accuracy %.3f | Precision %.3f | Recall %.3f | F1 %.3f | FPR %.3f | TP %d FP %d TN %d FN %d",
                    labelled,accuracy,precision,recall,f1,fpr,tp,fp,tn,fn));
        }else evaluationInfo.setText("No TRUE_LABEL values imported; evaluation metrics unavailable.");
        refreshOverview();status.setText("Prediction results imported: "+path.getFileName());
    }
    private static double ratio(long a,long b){return b==0?0:(double)a/b;}
    private interface CsvWriter {void write(CSVPrinter printer)throws IOException;}
    private void export(String filename,String[] names,CsvWriter content){JFileChooser c=new JFileChooser();c.setSelectedFile(new File(filename));
        if(c.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        try(Writer w=Files.newBufferedWriter(c.getSelectedFile().toPath(),StandardCharsets.UTF_8);
            CSVPrinter csv=new CSVPrinter(w,CSVFormat.DEFAULT)){
            csv.printRecord((Object[])names);content.write(csv);status.setText("Exported: "+c.getSelectedFile().getName());
        }catch(IOException ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Export failed",JOptionPane.ERROR_MESSAGE);}}
    private void exportQuality(){export("quality_report.csv",new String[]{"Indicator","Count"},csv->{for(int i=0;i<qualityCounts.length;i++)csv.printRecord(qualityNames[i],qualityCounts[i]);});}
    private void exportConsumers(){export("consumer_features.csv",new String[]{"CONS_NO","FLAG","MEAN","STD_DEV","MIN","MAX","CV","PAR","TOTAL","TREND","LAG7_AUTOCORR","CONSUMPTION_DEVIATION"},csv->{for(Consumer c:consumers){List<String> row=new ArrayList<>();row.add(c.id);row.add(c.label);for(String f:FEATURES)row.add(c.values.get(f));csv.printRecord(row);}});}
    private void exportAlerts(){export("alerts.csv",new String[]{"Consumer ID","Category","Details","Status"},csv->{for(Alert a:alerts)csv.printRecord(a.id,a.kind,a.details,a.status);});}
    private void exportPredictions(){export("prediction_cases.csv",new String[]{"Consumer ID","Tier 1 score","Tier 2 score","Status"},csv->{for(int i=0;i<detectionModel.getRowCount();i++)csv.printRecord(detectionModel.getValueAt(i,0),detectionModel.getValueAt(i,1),detectionModel.getValueAt(i,2),detectionModel.getValueAt(i,3));});}
    private static class MiniBars extends JPanel {
        private final String[] names;private double[] data;private final Color color;
        MiniBars(String[] names,double[] values,Color color){this.names=names;this.data=values.clone();this.color=color;setOpaque(false);setPreferredSize(new Dimension(250,260));}
        void setValues(double[] values){data=values.clone();repaint();}
        @Override protected void paintComponent(Graphics gg){super.paintComponent(gg);Graphics2D g=(Graphics2D)gg.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(),h=getHeight();int left=12,right=12,top=6;double max=0;
            for(double v:data)if(Double.isFinite(v))max=Math.max(max,Math.abs(v));if(max==0)max=1;
            int row=Math.max(22,(h-top-5)/Math.max(1,names.length));
            for(int i=0;i<names.length;i++){
                int y=top+i*row; if(y+row>h+4)break;
                g.setFont(new Font("SansSerif",Font.PLAIN,11));g.setColor(MUTED);
                String name=names[i].replace('_',' '); if(name.length()>21)name=name.substring(0,19)+"…";
                g.drawString(name,left,y+11);
                int barY=y+15,barWidth=Math.max(0,w-left-right);
                g.setColor(new Color(235,240,247));g.fillRoundRect(left,barY,barWidth,Math.max(5,row-20),7,7);
                double v=i<data.length&&Double.isFinite(data[i])?Math.abs(data[i]):0;
                g.setColor(color);g.fillRoundRect(left,barY,(int)(barWidth*v/max),Math.max(5,row-20),7,7);
            }g.dispose();}
    }
    public static void main(String[] args){SwingUtilities.invokeLater(()->new App().setVisible(true));}
}
