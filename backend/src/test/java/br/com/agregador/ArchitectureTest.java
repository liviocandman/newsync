package br.com.agregador;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final com.tngtech.archunit.core.domain.JavaClasses APPLICATION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("br.com.agregador");

  @Test
  void parsingAndControllersCannotOpenExternalConnections() {
    noClasses()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .or()
        .haveSimpleName("FeedParser")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.apache.hc..", "java.net.http..")
        .check(APPLICATION);
    noClasses()
        .that()
        .haveSimpleName("FeedParser")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.net.URL")
        .check(APPLICATION);
  }

  @Test
  void apacheHttpIsConfinedToTheSinglePublicFeedClient() {
    noClasses()
        .that()
        .haveNameNotMatching("br\\.com\\.agregador\\.ingestion\\.PublicFeedClient(?:\\$.*)?")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.apache.hc..", "java.net.http..")
        .check(APPLICATION);
  }

  @Test
  void productControllersDoNotContainSqlOrCallTheCollector() {
    noClasses()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .and()
        .doNotHaveSimpleName("ReadinessController")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.jdbc..", "java.sql..", "br.com.agregador.ingestion..")
        .check(APPLICATION);
  }
}
