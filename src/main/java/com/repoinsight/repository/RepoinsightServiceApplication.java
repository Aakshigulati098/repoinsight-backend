package com.repoinsight.repository;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class RepoinsightServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RepoinsightServiceApplication.class, args);
	}

}
