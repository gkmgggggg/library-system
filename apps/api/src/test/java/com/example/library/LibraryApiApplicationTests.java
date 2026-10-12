package com.example.library;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:1/library_test}","spring.datasource.username=${TEST_DATABASE_USER:library_test}","spring.datasource.password=${TEST_DATABASE_PASSWORD:}"})
class LibraryApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
