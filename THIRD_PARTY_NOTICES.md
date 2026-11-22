# Third-party dependencies

The source distribution declares, but does not include, the following runtime libraries. The download script obtains their unmodified Maven Central artifacts and checks published checksums. Maven downloads preserve the project's normal dependency metadata.

| Library | Version | Upstream license |
|---|---|---|
| H2 Database Engine | 2.3.232 | MPL 2.0 or EPL 1.0 |
| TwelveMonkeys ImageIO and common modules | 3.15.3 | BSD 3-Clause |
| MySQL Connector/J | 9.2.0 | GPL 2.0 with Universal FOSS Exception 1.0 |

The license files supplied inside each downloaded JAR remain authoritative. Review the applicable MySQL Connector/J license before redistributing it as part of another product. The MySQL driver is only needed for the optional MySQL mode.
