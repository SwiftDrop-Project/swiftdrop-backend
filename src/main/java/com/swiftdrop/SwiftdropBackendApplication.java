package com.swiftdrop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SwiftdropBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(SwiftdropBackendApplication.class, args);
	}

}
