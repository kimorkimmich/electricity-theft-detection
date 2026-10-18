
package com.electricitytheft;

import java.util.HashMap;
import java.util.Map;

import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;

public class XGBoostTest {

    public static void main(String[] args) throws Exception {

        // Small synthetic dataset for installation testing.
        // Each record contains two features.
        float[] data = {
            1f, 2f,
            2f, 3f,
            3f, 4f,
            4f, 5f,
            8f, 9f,
            9f, 10f
        };

        float[] labels = {0f, 0f, 0f, 0f, 1f, 1f};

        DMatrix train = new DMatrix(data, 6, 2, Float.NaN);
        train.setLabel(labels);

        Map<String, Object> params = new HashMap<>();
        params.put("objective", "binary:logistic");
        params.put("eval_metric", "logloss");
        params.put("max_depth", 3);
        params.put("eta", 0.1);
        params.put("tree_method", "hist");
        params.put("seed", 42);

        // Class-weighting example: 4 normal / 2 theft.
        params.put("scale_pos_weight", 2.0);

        Map<String, DMatrix> watches = new HashMap<>();
        watches.put("train", train);

        Booster model = XGBoost.train(
                train, params, 20, watches, null, null);

        float[][] predictions = model.predict(train);

        System.out.println("XGBoost training successful.");

        for (int i = 0; i < predictions.length; i++) {
            System.out.printf(
                    "Customer %d | Theft probability: %.4f%n",
                    i + 1,
                    predictions[i][0]);
        }

        model.dispose();
        train.dispose();
    }
}
