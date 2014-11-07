ACS AEM ARS Project
========
ARS - Analytics Report Scheduler

This project is inspired by some code written by a colleague for a customer (that never used it). The ARS bundle schedules
a report with Adobe Analytics (SiteCatalyst) and retrieves and saves it as a json text file in the repository. So far,
this can only run a 'top' pages report query.

AEM Versions Tested:

5.6.1

This bundle requires a configuration to be active. Without an OSGi configuration it will not run.

Configuration Options:

- Frequency - the number of seconds between execution
- Report Suite - the report suite to use to retrieve the report data
- Number of Results - the number of results to return
- Analytics Node Name - the name of the cloud service node that contains represents the credentials for your analytics in AEM/CQ
- Report Name - the name of the node to save the results as. (saves under /var/acs-analytics)
- Property or Evar - the prop name or evar name to filter the results as. If blank, this disables filtering
- Property/Evar values - multi-field list of values of the property listed above
- Number of Days - the number of days into the past to report on. Start date is 'now' and end date is now minus number of days

Building
--------

This project uses Maven for building. Common commands:

From the root directory, run ``mvn -PautoInstallPackage clean install`` to build the bundle and content package and install to a CQ instance.

From the bundle directory, run ``mvn -PautoInstallBundle clean install`` to build *just* the bundle and install to a CQ instance.

Using with VLT
--------------

To use vlt with this project, first build and install the package to your local CQ instance as described above. Then cd to `content/src/main/content/jcr_root` and run

    vlt --credentials admin:admin checkout -f ../META-INF/vault/filter.xml --force http://localhost:4502/crx

Once the working copy is created, you can use the normal ``vlt up`` and ``vlt ci`` commands.

Specifying CRX Host/Port
------------------------

The CRX host and port can be specified on the command line with:
mvn -Dcrx.host=otherhost -Dcrx.port=5502 <goals>


