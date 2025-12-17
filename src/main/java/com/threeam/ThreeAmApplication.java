package com.threeam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling   // 결제 재동기화(PaymentSyncScheduler) 등 주기 작업용
@SpringBootApplication
public class ThreeAmApplication {

	public static void main(String[] args) {
		SpringApplication.run(ThreeAmApplication.class, args);
	}

}
