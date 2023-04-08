# IMAP Dedup

It's a small Scala 3 program that connects to an IMAP server and scans it for duplicates emails.

Duplicates are detected using 2 details: the message content (md5 hash of it) and the date of the message.
This is enough to detect accidental copies of emails, e.g. due to mistakes during migrations or similar.

## Usage

The program is entirely configured using environment variables:

* `IMAP_HOST`: The IMAP server to connect to
* `IMAP_PORT`: The IMAP server port to connect to
* `IMAP_USERNAME`: The username to login with
* `IMAP_PASSWORD`: The password to login with
* `IGNORE_FOLDERS`: An optional comma-separated list of folders (full names) to ignore
