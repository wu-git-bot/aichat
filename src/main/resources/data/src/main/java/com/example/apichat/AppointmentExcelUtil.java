package com.example.apichat;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class AppointmentExcelUtil {

    private static final Path EXCEL_PATH = Paths.get("appointments.xlsx");

    public static List<Map<String, String>> readAppointments() throws IOException {
        List<Map<String, String>> list = new ArrayList<>();
        if (!Files.exists(EXCEL_PATH)) {
            return list;
        }

        try (FileInputStream fis = new FileInputStream(EXCEL_PATH.toFile());
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // 获取表头
            List<String> headers = new ArrayList<>();
            if (rowIterator.hasNext()) {
                Row headerRow = rowIterator.next();
                for (Cell cell : headerRow) {
                    headers.add(cell.getStringCellValue());
                }
            }

            // 读取每一行数据
            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Map<String, String> map = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = row.getCell(i);
                    String value = (cell == null) ? "" : getCellValue(cell);
                    map.put(headers.get(i), value);
                }
                list.add(map);
            }
        }

        return list;
    }

    private static String getCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }
}
