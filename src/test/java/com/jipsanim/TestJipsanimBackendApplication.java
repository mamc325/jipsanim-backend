package com.jipsanim;

import org.springframework.boot.SpringApplication;

public class TestJipsanimBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(JipsanimBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
