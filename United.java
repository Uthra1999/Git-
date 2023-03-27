package automatiom;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

public class United {

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		System.setProperty("webdriver.chrome.driver", "C:\\Users\\UthraK\\Downloads\\chromedriver.exe");
		   // WebDriver driver = new ChromeDriver();
		    ChromeOptions ops = new ChromeOptions();
		    ops.addArguments("--remote-allow-origins=*");
		    WebDriver driver = new ChromeDriver(ops);
	        driver.get("https://www.goodreads.com/");
	        WebElement signin = driver.findElement(By.linkText("Sign In"));
	        signin.click();
	        WebElement signinwithemail = driver.findElement(By.linkText("Sign in with email"));
	        signinwithemail.click();
	        WebElement email = driver.findElement(By.id("ap_email"));
	        email.sendKeys("ultimateuthra@gmail.com");
	        WebElement password = driver.findElement(By.id("ap_password"));
	        password.sendKeys("muppets123");
	        driver.findElement(By.id("signInSubmit")).click();
	        WebElement a = driver.findElement(By.className("currentlyReadingShelf"));
	        assert a.isDisplayed();
	        System.out.println("pass");
	        WebElement book = driver.findElement(By.xpath("/html/body/div[4]/main/div[1]/section[1]/div/div/section/span/div[1]/div/div[1]/a));
	        book.click();
	        WebElement b = driver.findElement(By.className( "Overlay__window"));
	        assert b.isDisplayed();
	        System.out.println("pass");

	      

	}

}
