
package com.electricitytheft;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class DataLoader {

    public static List<String[]> loadPreview(
            Path filePath, int maxRows) throws IOException {

        if (maxRows < 0) {
            throw new IllegalArgumentException(
                    "maxRows cannot be negative");
        }

        List<String[]> rows = new ArrayList<>();

        try (
            Reader reader = Files.newBufferedReader(
                    filePath, StandardCharsets.UTF_8);

            CSVParser parser =
                    CSVFormat.DEFAULT.parse(reader)
        ) {

            int count = 0;

            for (CSVRecord record : parser) {

                if (count >= maxRows) {
                    break;
                }

                String[] values = new String[record.size()];

                for (int i = 0; i < record.size(); i++) {
                    values[i] = record.get(i);
                }

                rows.add(values);
                count++;
            }
        }

        return rows;
    }
}
