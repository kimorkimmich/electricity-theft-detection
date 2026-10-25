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
