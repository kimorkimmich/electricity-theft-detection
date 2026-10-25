
package com.electricitytheft;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;

public class XGBoostTrainer {

    // Automatically uses all 17 features
    private static final int NUM_FEATURES =
            XGBoostDataLoader.FEATURE_NAMES.length;

    private static final int NUM_FOLDS = 5;
    private static final int BOOSTING_ROUNDS = 150;
    private static final double THRESHOLD = 0.50;
    private static final Path OUTPUT_DIR = Path.of("results", "threshold_sweep");
    private static final double MIN_REQUIRED_RECALL = 0.80;

    public static void main(String[] args) throws Exception {

        System.out.println("====================================");
        System.out.println("POWER GUARD - XGBOOST BASELINE");
        System.out.println("FEATURES: " + NUM_FEATURES);
        System.out.println("====================================");

        // STEP 1: Load consumers and extracted features
        List<XGBoostDataLoader.Consumer> consumers =
                XGBoostDataLoader.load();

        List<XGBoostDataLoader.Consumer> development =
                new ArrayList<>();

        int holdoutCount = 0;
        int holdoutTheft = 0;

        // STEP 2: Separate development and test customers
        for (XGBoostDataLoader.Consumer c : consumers) {

            if (c.development) {
                development.add(c);
            } else {
                holdoutCount++;

                if (c.label == 1) {
                    holdoutTheft++;
                }
            }
        }

        System.out.println("Total customers: " + consumers.size());
        System.out.println("Development: " + development.size());
        System.out.println("Holdout: " + holdoutCount);
        System.out.println("Holdout theft labels: " + holdoutTheft);
        System.out.println("Final holdout excluded from CV.");

        if (development.isEmpty()) {
            throw new IllegalStateException(
                    "Development set is empty.");
        }

        // Store evaluation results from all five folds
        List<Double> foldPrecision = new ArrayList<>();
        List<Double> foldRecall = new ArrayList<>();
        List<Double> foldF1 = new ArrayList<>();
        List<Double> foldAP = new ArrayList<>();
        List<Double> foldFPR = new ArrayList<>();
        List<Double> foldTrainingTime = new ArrayList<>();
        List<Double> foldInferenceTime = new ArrayList<>();

        Files.createDirectories(OUTPUT_DIR);
        List<ValidationPrediction> allPredictions = new ArrayList<>();
        List<ThresholdResult> thresholdResults = new ArrayList<>();

        // STEP 3: Five-fold cross-validation
        for (int fold = 0; fold < NUM_FOLDS; fold++) {

            System.out.println("\n====================================");
            System.out.println("VALIDATION FOLD " + fold);
            System.out.println("====================================");

            List<XGBoostDataLoader.Consumer> training =
                    new ArrayList<>();

            List<XGBoostDataLoader.Consumer> validation =
                    new ArrayList<>();

            // Four folds for training, one for validation
            for (XGBoostDataLoader.Consumer c : development) {

                if (c.fold == fold) {
                    validation.add(c);
                } else {
                    training.add(c);
                }
            }

            if (training.isEmpty() || validation.isEmpty()) {
                throw new IllegalStateException(
                        "Empty training or validation fold: " + fold);
            }

            // STEP 4: Count normal and theft customers
            int positives = 0;
            int negatives = 0;

            for (XGBoostDataLoader.Consumer c : training) {

                if (c.label == 1) {
                    positives++;
                } else {
                    negatives++;
                }
            }

            if (positives == 0 || negatives == 0) {
                throw new IllegalStateException(
                        "Training fold contains only one class.");
            }

            // STEP 5: Calculate class imbalance weight
            double scalePosWeight =
                    (double) negatives / positives;

            System.out.println("Training: " + training.size());
            System.out.println("Validation: " + validation.size());
            System.out.println("Normal customers: " + negatives);
            System.out.println("Theft customers: " + positives);

            System.out.printf(
                    "scale_pos_weight: %.4f%n",
                    scalePosWeight);

            DMatrix trainMatrix = null;
            DMatrix validationMatrix = null;
            Booster model = null;

            try {

                // STEP 6: Convert feature vectors to DMatrix
                trainMatrix = createMatrix(training);
                validationMatrix = createMatrix(validation);

                // STEP 7: XGBoost hyperparameters
                Map<String, Object> params = new HashMap<>();

                // Binary classification
                params.put("objective", "binary:logistic");
                params.put("booster", "gbtree");

                // Histogram-based tree construction
                params.put("tree_method", "hist");

                // Baseline hyperparameters
                params.put("max_depth", 5);
                params.put("eta", 0.05);
                params.put("min_child_weight", 1);

                // Random sampling
                params.put("subsample", 0.8);
                params.put("colsample_bytree", 0.8);

                // Regularization
                params.put("gamma", 0.0);
                params.put("lambda", 1.0);
                params.put("alpha", 0.0);

                // Class imbalance handling
                params.put(
                        "scale_pos_weight",
                        scalePosWeight);

                // Evaluation metric
                params.put("eval_metric", "aucpr");

                // Reproducibility
                params.put("seed", 42);
                params.put("verbosity", 0);

                // Empty watches: no per-round logging
                Map<String, DMatrix> watches =
                        new HashMap<>();

                // STEP 8: Train XGBoost
                long trainingStart = System.nanoTime();

                model = XGBoost.train(
                        trainMatrix,
                        params,
                        BOOSTING_ROUNDS,
                        watches,
                        null,
                        null);

                double trainingSeconds =
                        (System.nanoTime() - trainingStart)
                                / 1e9;

                // STEP 9: Predict validation probabilities
                long inferenceStart = System.nanoTime();

                float[][] predictions =
                        model.predict(validationMatrix);

                double inferenceSeconds =
                        (System.nanoTime() - inferenceStart)
                                / 1e9;

                foldTrainingTime.add(trainingSeconds);
                foldInferenceTime.add(inferenceSeconds);

                // STEP 10: Initialize confusion matrix
                int tp = 0;
                int tn = 0;
                int fp = 0;
                int fn = 0;

                double[] scores =
                        new double[validation.size()];

                int[] labels =
                        new int[validation.size()];

                // STEP 11: Evaluate each prediction
                for (int i = 0; i < validation.size(); i++) {

                    double probability = predictions[i][0];
                    int actual = validation.get(i).label;

                    int predicted =
                            probability >= THRESHOLD ? 1 : 0;

                    scores[i] = probability;
                    labels[i] = actual;

                    if (predicted == 1 && actual == 1) {
                        tp++;
                    } else if (predicted == 0 && actual == 0) {
                        tn++;
                    } else if (predicted == 1) {
                        fp++;
                    } else {
                        fn++;
                    }
                }

                // Evaluate all candidate thresholds using this fold's predictions.
                for (int step = 1; step <= 19; step++) {
                    double threshold = step * 0.05;
                    thresholdResults.add(evaluateThreshold(fold, scores, labels, threshold));
                }
                for (int i = 0; i < scores.length; i++) {
                    allPredictions.add(new ValidationPrediction(fold, labels[i], scores[i]));
                }

                // STEP 12: Calculate evaluation metrics
                double precision =
                        divide(tp, tp + fp);

                double recall =
                        divide(tp, tp + fn);

                double f1 =
                        divide(
                                2.0 * precision * recall,
                                precision + recall);

                double fpr =
                        divide(fp, fp + tn);

                double averagePrecision =
                        averagePrecision(scores, labels);

                foldPrecision.add(precision);
                foldRecall.add(recall);
                foldF1.add(f1);
                foldFPR.add(fpr);
                foldAP.add(averagePrecision);

                // STEP 13: Display fold results
                System.out.println("\nRESULTS");

                System.out.printf(
                        "Precision: %.4f%n", precision);

                System.out.printf(
                        "Recall: %.4f%n", recall);

                System.out.printf(
                        "F1-score: %.4f%n", f1);

                System.out.printf(
                        "Average Precision: %.4f%n",
                        averagePrecision);

                System.out.printf(
                        "False Positive Rate: %.4f%n", fpr);

                System.out.printf(
                        "Training time: %.3f seconds%n",
                        trainingSeconds);

                System.out.printf(
                        "Inference time: %.3f seconds%n",
                        inferenceSeconds);

                System.out.println("\nConfusion Matrix:");
                System.out.println(
                        "TN = " + tn + " | FP = " + fp);

                System.out.println(
                        "FN = " + fn + " | TP = " + tp);

            } finally {

                // STEP 14: Release native resources
                if (model != null) {
                    model.dispose();
                }

                if (trainMatrix != null) {
                    trainMatrix.dispose();
                }

                if (validationMatrix != null) {
                    validationMatrix.dispose();
                }
            }
        }

        // STEP 15: Calculate mean CV results
        System.out.println("\n====================================");
        System.out.println("5-FOLD CROSS-VALIDATION SUMMARY");
        System.out.println("====================================");

        System.out.printf(
                "Mean Precision: %.4f%n",
                mean(foldPrecision));

        System.out.printf(
                "Mean Recall: %.4f%n",
                mean(foldRecall));

        System.out.printf(
                "Mean F1-score: %.4f%n",
                mean(foldF1));

        System.out.printf(
                "Mean Average Precision: %.4f%n",
                mean(foldAP));

        System.out.printf(
                "Mean False Positive Rate: %.4f%n",
                mean(foldFPR));

        System.out.printf(
                "Mean Training Time: %.3f seconds%n",
                mean(foldTrainingTime));

        System.out.printf(
                "Mean Inference Time: %.3f seconds%n",
                mean(foldInferenceTime));

        writeOutputs(thresholdResults, allPredictions);
        printThresholdSummary(thresholdResults);
        System.out.println("\nFinal holdout remains untouched.");
        System.out.println(
                "17-feature weighted XGBoost baseline completed.");
    }

    private record ValidationPrediction(int fold, int actual, double score) {}

    private record ThresholdResult(int fold, double threshold,
                                   int tn, int fp, int fn, int tp,
                                   double precision, double recall,
                                   double f1, double fpr) {}

    private static ThresholdResult evaluateThreshold(
            int fold, double[] scores, int[] labels, double threshold) {
        int tn = 0, fp = 0, fn = 0, tp = 0;
        for (int i = 0; i < scores.length; i++) {
            boolean predicted = scores[i] >= threshold;
            if (predicted && labels[i] == 1) tp++;
            else if (predicted) fp++;
            else if (labels[i] == 1) fn++;
            else tn++;
        }
        double precision = divide(tp, tp + fp);
        double recall = divide(tp, tp + fn);
        double f1 = divide(2.0 * precision * recall, precision + recall);
        double fpr = divide(fp, fp + tn);
        return new ThresholdResult(fold, threshold, tn, fp, fn, tp,
                precision, recall, f1, fpr);
    }

    private static void writeOutputs(List<ThresholdResult> results,
                                     List<ValidationPrediction> predictions)
            throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(
                OUTPUT_DIR.resolve("threshold_results_by_fold.csv"), StandardCharsets.UTF_8)) {
            writer.write("fold,threshold,tn,fp,fn,tp,precision,recall,f1,fpr\n");
            for (ThresholdResult r : results) {
                writer.write(String.format(Locale.ROOT,
                        "%d,%.2f,%d,%d,%d,%d,%.8f,%.8f,%.8f,%.8f%n",
                        r.fold, r.threshold, r.tn, r.fp, r.fn, r.tp,
                        r.precision, r.recall, r.f1, r.fpr));
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                OUTPUT_DIR.resolve("oof_predictions.csv"), StandardCharsets.UTF_8)) {
            writer.write("fold,actual_label,theft_probability\n");
            for (ValidationPrediction p : predictions) {
                writer.write(String.format(Locale.ROOT,
                        "%d,%d,%.10f%n", p.fold, p.actual, p.score));
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                OUTPUT_DIR.resolve("threshold_summary.csv"), StandardCharsets.UTF_8)) {
            writer.write("threshold,tn,fp,fn,tp,precision,recall,f1,fpr,mean_fold_f1,mean_fold_fpr\n");
            for (int step = 1; step <= 19; step++) {
                double t = step * 0.05;
                int tn = 0, fp = 0, fn = 0, tp = 0;
                double f1Sum = 0, fprSum = 0;
                int folds = 0;
                for (ThresholdResult r : results) {
                    if (Math.abs(r.threshold - t) < 1e-9) {
                        tn += r.tn; fp += r.fp; fn += r.fn; tp += r.tp;
                        f1Sum += r.f1; fprSum += r.fpr;
                        folds++;
                    }
                }
                double precision = divide(tp, tp + fp);
                double recall = divide(tp, tp + fn);
                double f1 = divide(2 * precision * recall, precision + recall);
                double fpr = divide(fp, fp + tn);
                writer.write(String.format(Locale.ROOT,
                        "%.2f,%d,%d,%d,%d,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f%n",
                        t, tn, fp, fn, tp, precision, recall, f1, fpr,
                        divide(f1Sum, folds), divide(fprSum, folds)));
            }
        }
        System.out.println("Saved threshold results to " + OUTPUT_DIR.toAbsolutePath());
    }

    private static void printThresholdSummary(List<ThresholdResult> results) {
        double bestF1 = -1;
        double bestF1Threshold = Double.NaN;
        double bestConstrainedFpr = Double.POSITIVE_INFINITY;
        double bestConstrainedRecall = -1;
        double bestConstrainedThreshold = Double.NaN;
        System.out.println("\n====================================");
        System.out.println("THRESHOLD SWEEP (POOLED OOF CONFUSION MATRICES)");
        System.out.println("====================================");
        System.out.println("Threshold  Precision  Recall   F1      FPR");
        for (int step = 1; step <= 19; step++) {
            double t = step * 0.05;
            int tn = 0, fp = 0, fn = 0, tp = 0;
            for (ThresholdResult r : results) {
                if (Math.abs(r.threshold - t) < 1e-9) {
                    tn += r.tn; fp += r.fp; fn += r.fn; tp += r.tp;
                }
            }
            double precision = divide(tp, tp + fp);
            double recall = divide(tp, tp + fn);
            double f1 = divide(2 * precision * recall, precision + recall);
            double fpr = divide(fp, fp + tn);
            System.out.printf(Locale.ROOT, "%.2f       %.4f     %.4f  %.4f  %.4f%n",
                    t, precision, recall, f1, fpr);
            if (f1 > bestF1) {
                bestF1 = f1;
                bestF1Threshold = t;
            }
            if (recall >= MIN_REQUIRED_RECALL
                    && (fpr < bestConstrainedFpr
                        || (fpr == bestConstrainedFpr && recall > bestConstrainedRecall))) {
                bestConstrainedFpr = fpr;
                bestConstrainedRecall = recall;
                bestConstrainedThreshold = t;
            }
        }
        System.out.printf(Locale.ROOT, "Best pooled OOF F1 threshold: %.2f (F1 %.4f)%n",
                bestF1Threshold, bestF1);
        if (Double.isNaN(bestConstrainedThreshold)) {
            System.out.printf(Locale.ROOT,
                    "No threshold reached the required %.0f%% recall.%n", MIN_REQUIRED_RECALL * 100);
        } else {
            System.out.printf(Locale.ROOT,
                    "Lowest-FPR threshold with recall >= %.0f%%: %.2f (FPR %.4f, recall %.4f)%n",
                    MIN_REQUIRED_RECALL * 100, bestConstrainedThreshold,
                    bestConstrainedFpr, bestConstrainedRecall);
        }
        System.out.println("NOTE: Thresholds above are selected on the same OOF results used");
        System.out.println("to display metrics; selected-threshold performance is not nested-CV unbiased.");
    }

    // Convert Consumer objects to XGBoost DMatrix
    private static DMatrix createMatrix(
            List<XGBoostDataLoader.Consumer> consumers)
            throws Exception {

        int rows = consumers.size();

        float[] data = new float[rows * NUM_FEATURES];
        float[] labels = new float[rows];

        for (int i = 0; i < rows; i++) {

            XGBoostDataLoader.Consumer c =
                    consumers.get(i);

            if (c.features.length != NUM_FEATURES) {
                throw new IllegalArgumentException(
                        "Incorrect number of features.");
            }

            System.arraycopy(
                    c.features,
                    0,
                    data,
                    i * NUM_FEATURES,
                    NUM_FEATURES);

            labels[i] = c.label;
        }

        DMatrix matrix = new DMatrix(
                data,
                rows,
                NUM_FEATURES,
                Float.NaN);

        matrix.setLabel(labels);

        return matrix;
    }

    // Average Precision for ranked predictions
    private static double averagePrecision(
            double[] scores,
            int[] labels) {

        Integer[] order = new Integer[scores.length];

        int positiveCount = 0;

        for (int i = 0; i < scores.length; i++) {

            order[i] = i;

            if (labels[i] == 1) {
                positiveCount++;
            }
        }

        if (positiveCount == 0) {
            return 0.0;
        }

        // Sort by highest predicted probability
        Arrays.sort(
                order,
                Comparator.comparingDouble(
                        (Integer i) -> scores[i])
                        .reversed());

        int truePositives = 0;
        double precisionSum = 0.0;

        for (int rank = 0; rank < order.length; rank++) {

            int index = order[rank];

            if (labels[index] == 1) {

                truePositives++;

                precisionSum +=
                        (double) truePositives / (rank + 1);
            }
        }

        return precisionSum / positiveCount;
    }

    // Safe division
    private static double divide(
            double numerator,
            double denominator) {

        return denominator == 0
                ? 0.0
                : numerator / denominator;
    }

    // Calculate arithmetic mean
    private static double mean(List<Double> values) {

        return values.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }
}
