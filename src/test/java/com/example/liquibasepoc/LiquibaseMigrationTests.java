package com.example.liquibasepoc;

import com.example.liquibasepoc.customer.Customer;
import com.example.liquibasepoc.customer.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real changelog against a real SQL Server.
 *
 * spring.liquibase.enabled is false by default (migrations are a pipeline step, not a
 * startup side effect), so it is switched on here - the whole point of this test is to
 * exercise the migration.
 *
 * Simply loading the context proves a lot: Liquibase applied every changeset, and
 * ddl-auto=validate then confirmed the entities match the resulting schema. If the
 * changelog used syntax SQL Server rejects, or drifted from the entities, startup fails.
 */
@SpringBootTest(properties = "spring.liquibase.enabled=true")
@Import(TestcontainersConfiguration.class)
class LiquibaseMigrationTests {

	@Autowired
	private CustomerRepository customers;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void everyChangesetIsRecordedInTheLedger() {
		List<String> applied = jdbc.queryForList(
				"SELECT ID FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED", String.class);

		assertThat(applied).containsExactly(
				"POC-1-create-customer-table",
				"POC-2-add-phone-number");
	}

	@Test
	void customerTableHasTheExpectedColumns() {
		List<String> columns = jdbc.queryForList(
				"SELECT name FROM sys.columns WHERE object_id = OBJECT_ID('customer') ORDER BY column_id",
				String.class);

		assertThat(columns).containsExactly("id", "first_name", "email", "phone_number");
	}

	@Test
	void customerRoundTripsThroughTheMigratedSchema() {
		Customer ada = new Customer();
		ada.setFirstName("Ada");
		ada.setEmail("ada@example.com");
		ada.setPhoneNumber("+44 20 7946 0958");

		customers.save(ada);

		Optional<Customer> found = customers.findByEmail("ada@example.com");
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isNotNull();               // IDENTITY populated
		assertThat(found.get().getPhoneNumber()).isEqualTo("+44 20 7946 0958");
	}

	@Test
	void phoneNumberIsNullableSoOlderCodePathsStillInsert() {
		Customer alan = new Customer();
		alan.setFirstName("Alan");
		alan.setEmail("alan@example.com");
		// no phone number - POC-2 added the column as nullable on purpose

		customers.save(alan);

		assertThat(customers.findByEmail("alan@example.com")).isPresent();
	}

}
