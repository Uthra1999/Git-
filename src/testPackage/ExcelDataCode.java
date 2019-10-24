package testPackage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class ExcelDataCode {

	public static void main(String[] args) throws Exception {
          File f= new File("Excel File path with extension");
          FileInputStream fis = new FileInputStream(f);
          Workbook wb = new XSSFWorkbook(fis);
          Sheet S = wb.getSheetAt(0);
          int rowCount = S.getLastRowNum()-S.getFirstRowNum();
          for(int i = 0;i<rowCount+1;i++){
        	  Row row = S.getRow(i);
        	  for(int j=0;j<row.getLastCellNum();j++) {
        		  System.out.println(row.getCell(j).getStringCellValue());     		  
        	  
          
          
	}

	
	}

	}
}

