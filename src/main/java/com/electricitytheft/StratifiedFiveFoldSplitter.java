
package com.electricitytheft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class StratifiedFiveFoldSplitter {

    private static final int K = 5;
    private static final int SEED = 42;
    private static final double TEST_RATIO = 0.15;

    private static final Path INPUT =
            Paths.get("dataset", "sgcc_cleaned.csv");

    private static final Path OUTPUT =
            Paths.get("dataset", "sgcc_split_assignments.csv");

    static class Customer {
        String id;
        int flag;
        int fold;

        Customer(String id, int flag) {
            this.id = id;
            this.flag = flag;
            this.fold = -1;
        }
    }

    public static void main(String[] args) throws Exception {

        List<Customer> normal = new ArrayList<>();
        List<Customer> fraud = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        // STEP 1: Read cleaned SGCC dataset
        try (BufferedReader reader =
                     Files.newBufferedReader(INPUT, StandardCharsets.UTF_8)) {

            String header = reader.readLine();

            if (header == null) {
                throw new IllegalArgumentException("CSV is empty.");
            }

            String[] columns = header.replace("\uFEFF", "").split(",", -1);

            int idIndex = -1;
            int flagIndex = -1;

            for (int i = 0; i < columns.length; i++) {
                String name = columns[i].trim()
                        .replace("\"", "");

                if (name.equalsIgnoreCase("CONS_NO")) {
                    idIndex = i;
                }

                if (name.equalsIgnoreCase("FLAG")) {
                    flagIndex = i;
                }
            }

            if (idIndex == -1 || flagIndex == -1) {
                throw new IllegalArgumentException(
                        "Required columns CONS_NO and FLAG not found.");
            }

            String line;
            int lineNumber = 1;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] values = line.split(",", -1);

                if (values.length != columns.length) {
                    throw new IllegalArgumentException(
                            "Unexpected column count at line " + lineNumber);
                }

                String id = values[idIndex].trim().replace("\"", "");
                String label = values[flagIndex].trim().replace("\"", "");

                if (id.isEmpty() || !ids.add(id)) {
                    throw new IllegalArgumentException(
                            "Missing or duplicate customer ID at line "
                                    + lineNumber);
                }

                int flag = switch (label) {
                case "0" -> 0;
                case "1" -> 1;
                default -> throw new IllegalArgumentException(
                        "Invalid FLAG at line " + lineNumber);
            };
                Customer customer = new Customer(id, flag);

                if (flag == 0) {
                    normal.add(customer);
                } else {
                    fraud.add(customer);
                }
            }
        }

        // STEP 2: Shuffle each class reproducibly
        Collections.shuffle(normal, new Random(SEED));
        Collections.shuffle(fraud, new Random(SEED + 1));

        // STEP 3: Reserve 15% of each class for testing
        List<Customer> test = new ArrayList<>();
        List<Customer> development = new ArrayList<>();

        splitClass(normal, test, development);
        splitClass(fraud, test, development);

        // STEP 4: Create five stratified folds
        assignFolds(development, 0);
        assignFolds(development, 1);

        // STEP 5: Save customer assignments
        try (BufferedWriter writer =
                     Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8)) {

            writer.write("CONS_NO,FLAG,SPLIT,CV_FOLD");
            writer.newLine();

            for (Customer c : development) {
                writer.write(c.id + "," + c.flag
                        + ",development," + c.fold);
                writer.newLine();
            }

            for (Customer c : test) {
                writer.write(c.id + "," + c.flag + ",test,");
                writer.newLine();
            }
        }

        // STEP 6: Display statistics
        System.out.println("\nSTRATIFIED 5-FOLD SPLIT");
        System.out.println("--------------------------");

        System.out.println("Total customers: "
                + (development.size() + test.size()));

        System.out.println("Development: " + development.size());
        System.out.println("Test: " + test.size());

        for (int fold = 0; fold < K; fold++) {
            int normalCount = 0;
            int fraudCount = 0;

            for (Customer c : development) {
                if (c.fold == fold) {
                    if (c.flag == 0) {
                        normalCount++;
                    } else {
                        fraudCount++;
                    }
                }
            }

            System.out.printf(
                    "Fold %d: Normal=%d, Fraud=%d, Total=%d%n",
                    fold,
                    normalCount,
                    fraudCount,
                    normalCount + fraudCount
            );
        }

        System.out.println("Saved: " + OUTPUT);
    }

    // Reserve approximately 15% for test
    private static void splitClass(
            List<Customer> source,
            List<Customer> test,
            List<Customer> development) {

        int testCount = (int) Math.round(source.size() * TEST_RATIO);

        for (int i = 0; i < source.size(); i++) {
            Customer c = source.get(i);

            if (i < testCount) {
                test.add(c);
            } else {
                development.add(c);
            }
        }
    }

    // Assign each class separately to five folds
    private static void assignFolds(
            List<Customer> development,
            int targetFlag) {

        int index = 0;

        for (Customer c : development) {
            if (c.flag == targetFlag) {
                c.fold = index % K;
                index++;
            }
        }
    }
}
