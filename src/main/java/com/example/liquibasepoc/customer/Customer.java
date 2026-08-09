package com.example.liquibasepoc.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "customer")
@Getter
@Setter
public class Customer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotBlank
	@Column(name = "first_name", nullable = false, length = 100)
	private String firstName;

	@Email
	@NotBlank
	@Column(name = "email", nullable = false, unique = true, length = 255)
	private String email;

	// Nullable, matching POC-2: existing rows have no phone number.
	@Column(name = "phone_number", length = 30)
	private String phoneNumber;

}
