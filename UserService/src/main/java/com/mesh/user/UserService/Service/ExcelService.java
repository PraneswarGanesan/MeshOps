    package com.mesh.user.UserService.Service;

    import org.apache.poi.ss.usermodel.Cell;
    import org.apache.poi.ss.usermodel.Row;
    import org.apache.poi.ss.usermodel.Sheet;
    import org.apache.poi.ss.usermodel.Workbook;
    import org.apache.poi.xssf.usermodel.XSSFWorkbook;
    import org.springframework.stereotype.Service;

    import java.io.InputStream;
    @Service
    public class ExcelService {
        public boolean isAdminUsername(String inputUsername) {
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("admins.xlsx");
                 Workbook workbook = new XSSFWorkbook(inputStream)) {

                Sheet sheet = workbook.getSheetAt(0);
                for (Row row : sheet) {
                    if (row.getRowNum() == 0) continue;

                    Cell usernameCell = row.getCell(0);

                    if (usernameCell != null) {
                        String username = usernameCell.getStringCellValue().trim().toLowerCase();
                        if (username.equalsIgnoreCase(inputUsername.trim())) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return false;
        }
    }
