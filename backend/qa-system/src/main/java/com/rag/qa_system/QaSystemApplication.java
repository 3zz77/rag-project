package com.rag.qa_system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.rag.qa_system.mapper")  // 扫描Mapper接口
public class QaSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(QaSystemApplication.class, args);
	}

}
