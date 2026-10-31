package com.swiftdrop;

import org.springframework.boot.SpringApplication;

public class TestSwiftdropBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(SwiftdropBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
