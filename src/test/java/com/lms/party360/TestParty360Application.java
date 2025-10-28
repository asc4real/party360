package com.lms.party360;

import org.springframework.boot.SpringApplication;

public class TestParty360Application {

	public static void main(String[] args) {
		SpringApplication.from(Party360Application::main).with(TestcontainersConfiguration.class).run(args);
	}

}
