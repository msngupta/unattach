# Unattach

See [homepage](https://unattach.app/) for more info.

## Legal
* [LICENSE](LICENSE)
* [PRIVACY](PRIVACY)

## Dependencies
* Install [Java 14](https://www.oracle.com/java/technologies/javase-downloads.html).
* Install [Maven](https://maven.apache.org/download.cgi).

## Build & Run
* Build with `mvn clean package`.
* Run with `java -jar target/client-3.0.0-jar-with-dependencies.jar`.

## File linking
* Check this repository for more details https://github.com/msngupta/unattach_redirector
* The file names (say abcd.xyz) in the modified emails are linked to https://unattach.appspot.com/get_file/abcd.xyz
* The links redirects to https://domain.xyz/path/abcd.xyz using a preset base_url (say https://domain.xyz/path)
* To set the base_url use the following syntax (https://unattach.appspot.com/set_path?base_path=https://domain.xyz/path/). Bookmark the link for easy access.
* Once the base_url is set using the above link, it is stored in the browser session as a cookie (unattach_base_url)


## Known Limitations
* On some emails, the app will fail with `OutOfMemoryError` even with the maximum heap size set to 2GB. This occurs
  when the Gmail API client library unpacks the downloaded email in local memory using a third-party JSON library, which
  appears to sometimes make inefficient use of the available memory. If this happens, the original email will remain
  intact, the memory will be recovered, and the processing will continue with the next email.
* The maximum number of search results is 500 despite the requested limit being much higher. This appears to be a
  restriction within the Gmail API service. If (downloading and) removing attachments through the Schedule feature,
  this limitation is not an issue, since a subsequent search will return different results. If only downloading
  attachments, a workaround is to manually add date ranges in the advanced search query.

## Contributions
Feel free to
[report issues](https://help.github.com/en/articles/creating-an-issue) and
[create pull requests](https://help.github.com/en/articles/creating-a-pull-request).

## Support Unattach
If you like Unattach:
* [Buy Developers a Coffee](https://unattach.app/#support) ☕, or
* [Become a Sponsor](https://github.com/sponsors/rokstrnisa) ❤️

## Current Sponsors
### Bronze
[![Smart Cities Transport](src/main/resources/smart-cities-transport-logo.png)](https://smartcitiestransport.com/)
