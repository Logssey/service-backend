# Re:Used release review recording fixture

This is an isolated demonstration, never a production release.
The env file contains a randomly generated, non-issued GitHub-shaped token.
Removing the file from this branch does not remove its copies in the private demo S3 prefix.

Verified image evidence: Logssey scan 68, finding 2176, isolated namespace reused-attack-lab.
The image was built from commit 1429ca5c161a4db6548752f3c02eea3822660a48.
This PR changes demonstration files only; it does not rebuild that image or prove an automated CI scan.
IaC evidence is the independent, unmerged infrastructure PR #75 (run 2).
Do not merge this branch into develop/main or deploy it to Re:Used production.
