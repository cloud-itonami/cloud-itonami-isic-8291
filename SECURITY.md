# Security Policy

This project handles corporate registry, officer/director and compliance
(sanctions/PEP) data. Treat vulnerabilities as potentially high impact even
when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real company or individual data exposure
- authorization bypass
- DisclosureGovernor bypass
- audit-ledger tampering
- over-disclosure beyond a contract's tier
- tenant isolation failures
- ingestion of private-life data through an undocumented field

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on subject data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real company/individual data outside this repository.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
