
package com.electricitytheft;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DataLoader {

    public static List<String[]> loadPreview(
            Path filePath, int maxRows) throws IOException {

        List<String[]> rows = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(
                filePath, StandardCharsets.UTF_8)) {

            String line;
            int count = 0;

            while ((line = reader.readLine()) != null
                    && count < maxRows + 1) {
                rows.add(parseCsvLine(line));
                count++;
            }
        }

        return rows;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean insideQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                if (insideQuotes && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    insideQuotes = !insideQuotes;
                }
            } else if (ch == ',' && !insideQuotes) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }

        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }
}
