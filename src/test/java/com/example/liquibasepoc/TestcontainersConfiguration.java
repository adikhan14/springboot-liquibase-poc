package com.example.liquibasepoc;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Throwaway SQL Server for tests. @ServiceConnection points spring.datasource.* at the
 * container's random port, so application.yaml's local url is ignored here.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MSSQLServerContainer sqlServerContainer() {
		// acceptLicense() is mandatory - the container refuses to start without ACCEPT_EULA=Y.
		// Image is linux/amd64 only; on Apple Silicon it runs under emulation.
		return new MSSQLServerContainer(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
				.acceptLicense();
	}

}
