
package com.electricitytheft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;

public class XGBoostTrainer {

    private static final int NUM_FEATURES = 10;
    private static final int NUM_FOLDS = 5;
    private static final int BOOSTING_ROUNDS = 150;
    private static final double THRESHOLD = 0.50;

    public static void main(String[] args)
            throws Exception {

        System.out.println("==================================");
        System.out.println("POWER GUARD - XGBOOST TRAINING");
        System.out.println("==================================");

        List<XGBoostDataLoader.Consumer> consumers =
                XGBoostDataLoader.load();

        List<XGBoostDataLoader.Consumer> development =
                new ArrayList<>();

        int holdoutCount = 0;
        int holdoutTheft = 0;

        for (XGBoostDataLoader.Consumer consumer : consumers) {

            if (consumer.development) {
                development.add(consumer);
            } else {
                holdoutCount++;

                if (consumer.label == 1) {
                    holdoutTheft++;
                }
            }
        }

        System.out.println("Total consumers: " + consumers.size());
        System.out.println("Development records: " + development.size());
        System.out.println("Holdout records: " + holdoutCount);
        System.out.println("Holdout theft labels: " + holdoutTheft);
        System.out.println(
                "Final holdout is excluded from cross-validation.");

        if (development.isEmpty()) {
            throw new IllegalStateException(
                    "No development records found.");
        }

        List<Double> foldF1 = new ArrayList<>();
        List<Double> foldRecall = new ArrayList<>();
        List<Double> foldPrecision = new ArrayList<>();
        List<Double> foldAP = new ArrayList<>();

        for (int fold = 0; fold < NUM_FOLDS; fold++) {

            System.out.println();
            System.out.println("==================================");
            System.out.println("VALIDATION FOLD " + fold);
            System.out.println("==================================");

            List<XGBoostDataLoader.Consumer> training =
                    new ArrayList<>();

            List<XGBoostDataLoader.Consumer> validation =
                    new ArrayList<>();

            for (XGBoostDataLoader.Consumer consumer
                    : development) {

                if (consumer.fold == fold) {
                    validation.add(consumer);
                } else {
                    training.add(consumer);
                }
            }

            if (training.isEmpty() || validation.isEmpty()) {
                throw new IllegalStateException(
                        "Empty training/validation fold: " + fold);
            }

            int positives = 0;
            int negatives = 0;

            for (XGBoostDataLoader.Consumer consumer : training) {

                if (consumer.label == 1) {
                    positives++;
                } else {
                    negatives++;
                }
            }

            if (positives == 0 || negatives == 0) {
                throw new IllegalStateException(
                        "Training fold contains only one class.");
            }

            double scalePosWeight =
                    (double) negatives / positives;

            System.out.println(
                    "Training records: " + training.size());

            System.out.println(
                    "Validation records: " + validation.size());

            System.out.printf(
                    "Positive class weight: %.4f%n",
                    scalePosWeight);

            DMatrix trainMatrix = null;
            DMatrix validationMatrix = null;
            Booster model = null;

            try {

                trainMatrix = createMatrix(training);

                validationMatrix = createMatrix(validation);

                Map<String, Object> params = new HashMap<>();

                params.put("objective", "binary:logistic");
                params.put("eval_metric", "logloss");
                params.put("tree_method", "hist");

                params.put("max_depth", 5);
                params.put("eta", 0.05);
                params.put("min_child_weight", 1);
                params.put("subsample", 0.8);
                params.put("colsample_bytree", 0.8);

                params.put(
                        "scale_pos_weight",
                        scalePosWeight);

                params.put("seed", 42);
                params.put("verbosity", 0);

                Map<String, DMatrix> watches =
                        new HashMap<>();

                model = XGBoost.train(
                        trainMatrix,
                        params,
                        BOOSTING_ROUNDS,
                        watches,
                        null,
                        null);

                float[][] predictions =
                        model.predict(validationMatrix);

                int tp = 0;
                int tn = 0;
                int fp = 0;
                int fn = 0;

                double[] scores =
                        new double[validation.size()];

                int[] labels =
                        new int[validation.size()];

                for (int i = 0; i < validation.size(); i++) {

                    double probability =
                            predictions[i][0];

                    int actual =
                            validation.get(i).label;

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
                foldAP.add(averagePrecision);

                System.out.println();
                System.out.println("RESULTS");

                System.out.printf(
                        "Precision: %.4f%n",
                        precision);

                System.out.printf(
                        "Recall: %.4f%n",
                        recall);

                System.out.printf(
                        "F1-score: %.4f%n",
                        f1);

                System.out.printf(
                        "Average Precision: %.4f%n",
                        averagePrecision);

                System.out.printf(
                        "False Positive Rate: %.4f%n",
                        fpr);

                System.out.println();
                System.out.println("Confusion Matrix:");

                System.out.println(
                        "TN = " + tn + " | FP = " + fp);

                System.out.println(
                        "FN = " + fn + " | TP = " + tp);

            } finally {

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

        System.out.println();
        System.out.println("==================================");
        System.out.println("5-FOLD CROSS-VALIDATION SUMMARY");
        System.out.println("==================================");

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

        System.out.println();
        System.out.println(
                "Final holdout remains untouched.");

        System.out.println(
                "Baseline XGBoost cross-validation completed.");
    }

    private static DMatrix createMatrix(
            List<XGBoostDataLoader.Consumer> consumers)
            throws Exception {

        int rows = consumers.size();

        float[] data = new float[rows * NUM_FEATURES];
        float[] labels = new float[rows];

        for (int i = 0; i < rows; i++) {

            XGBoostDataLoader.Consumer consumer =
                    consumers.get(i);

            if (consumer.features.length != NUM_FEATURES) {
                throw new IllegalArgumentException(
                        "Incorrect number of features.");
            }

            System.arraycopy(
                    consumer.features,
                    0,
                    data,
                    i * NUM_FEATURES,
                    NUM_FEATURES);

            labels[i] = consumer.label;
        }

        DMatrix matrix = new DMatrix(
                data,
                rows,
                NUM_FEATURES,
                Float.NaN);

        matrix.setLabel(labels);

        return matrix;
    }

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

    private static double divide(
            double numerator,
            double denominator) {

        return denominator == 0
                ? 0.0
                : numerator / denominator;
    }

    private static double mean(List<Double> values) {

        return values.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }
}
