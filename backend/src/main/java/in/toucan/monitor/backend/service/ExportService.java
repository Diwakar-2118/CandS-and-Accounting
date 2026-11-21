package in.toucan.monitor.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CSV/XLSX writer for any list. Nested values (evidence, payloads) are written as JSON text. */
@Service
public class ExportService {

    private final ObjectMapper om;

    public ExportService(ObjectMapper om) {
        this.om = om;
    }

    static List<String> columns(List<Map<String, Object>> rows) {
        Set<String> cols = new LinkedHashSet<>();
        rows.forEach(r -> cols.addAll(r.keySet()));
        return new ArrayList<>(cols);
    }

    String cell(Object v) {
        if (v == null) return "";
        if (v instanceof Map || v instanceof List) {
            try {
                return om.writeValueAsString(v);
            } catch (Exception e) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(v);
    }

    public void csv(List<Map<String, Object>> rows, OutputStream out) throws IOException {
        List<String> cols = columns(rows);
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write('﻿');
        w.write(String.join(",", cols.stream().map(ExportService::esc).toList()));
        w.write("\r\n");
        for (Map<String, Object> r : rows) {
            List<String> vals = new ArrayList<>();
            for (String c : cols) vals.add(esc(cell(r.get(c))));
            w.write(String.join(",", vals));
            w.write("\r\n");
        }
        w.flush();
    }

    static String esc(String s) {
        // Neutralise spreadsheet formula injection, then quote.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public void xlsx(List<Map<String, Object>> rows, String sheetName, OutputStream out) throws IOException {
        List<String> cols = columns(rows);
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500)) {
            Sheet sh = wb.createSheet(sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName);
            Row h = sh.createRow(0);
            for (int i = 0; i < cols.size(); i++) h.createCell(i).setCellValue(cols.get(i));
            int rn = 1;
            for (Map<String, Object> r : rows) {
                Row row = sh.createRow(rn++);
                for (int i = 0; i < cols.size(); i++) {
                    Object v = r.get(cols.get(i));
                    Cell c = row.createCell(i);
                    if (v instanceof BigDecimal b) c.setCellValue(b.doubleValue());
                    else if (v instanceof Number n) c.setCellValue(n.doubleValue());
                    else {
                        String s = cell(v);
                        c.setCellValue(s.length() > 32000 ? s.substring(0, 32000) : s);
                    }
                }
            }
            wb.write(out);
            wb.dispose();
        }
    }
}
