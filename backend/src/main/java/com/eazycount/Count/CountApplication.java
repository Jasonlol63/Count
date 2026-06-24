package com.eazycount.Count;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.eazycount")
@MapperScan("com.eazycount.dao")
public class CountApplication {

	public static void main(String[] args) {
		SpringApplication.run(CountApplication.class, args);
	}
	
}
