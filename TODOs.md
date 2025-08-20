# Remaining TODOs

This document outlines the remaining tasks needed to complete the end-to-end analytics pipeline for the PNR regression test results.

## Overview

The Java application successfully downloads test results from Jenkins and uploads them to HANA Cloud. The next phase involves setting up the analytics infrastructure to enable real-time reporting and visualization.

---

## 1. Create HANA Cloud Instance & Business Application Studio Setup

**Owner**: Brian Chen (or designated team member)

### Task Description

Set up a new HANA Cloud Instance on BTP Cockpit in the Cloud Foundry environment and configure Business Application Studio for development.

### Resources

- **Setup Guide**: [Create DB in BTP - HANA Cloud Instance](https://github.wdf.sap.corp/pages/i854112/buildtools-lib/docs/features/hanacloud-db/create-db-in-btp.html#add-sap-business-application-studio)

### Key Links

- **SAP BTP Control Center**: https://cp-control-client-uc2.cfapps.sap.hana.ondemand.com/index.html
- **SAP BTP Cockpit**: https://canary.cockpit.btp.int.sap/cockpit#

### Deliverables

- ✅ HANA Cloud instance provisioned
- ✅ Business Application Studio configured
- ✅ Database connection established for data upload

---

## 2. Create Calculation View & HDI Container Modeling

**Prerequisites**: HANA Cloud instance and Business Application Studio setup completed

### Task Description

Develop a calculation view in Business Application Studio to make the raw database tables consumable by SAP Analytics Cloud. SAC cannot directly consume HANA Cloud DB tables, so this modeling layer is essential.

### Process

1. **Create modeling layer** hosted on HDI container
2. **Deploy container** with calculation views
3. **Design data model** by:
   - Projecting relevant tables
   - Creating join nodes between TEST_RUN, TEST_SCENARIO, and TEST_RESULT
   - Specifying required columns for analytics

### Resources

- **Documentation**: [Working in SAP Business Application Studio](https://wiki.one.int.sap/wiki/display/ngdb/Howto+-+Working+in+SAP+Business+Application+Studio)

### Deliverables

- ✅ HDI container created and deployed
- ✅ Calculation view designed with proper joins
- ✅ Model captures test performance data for reporting

---

## 3. SAP Analytics Cloud Integration & Live Reporting

**Prerequisites**: Calculation view and HDI container deployed

### Task Description

Establish a live data connection between HANA Cloud and SAP Analytics Cloud to enable real-time performance regression reporting and dashboards.

### Key Requirements

- **HDI containers** must contain calculation views that determine data subsets available to SAC
- **SAC user** must have admin privileges to add connections
- **Database user** needs:
  - Roles and privileges for InA (Information Access)
  - Access to HANA artifacts required for data retrieval

### Resources

- **Primary Guide**: [Live Data Connection to SAP HANA Cloud](https://help.sap.com/docs/SAP_ANALYTICS_CLOUD/00f68c2e08b941f081002fd3691d86a7/5bd569b3f75f49f29e9ec251fd6a1386.html)
- **Tutorial**: [Create Live Connection Between HANA Cloud and SAC](https://developers.sap.com/tutorials/hana-cloud-connection-guide-1.html)

### Deliverables

- ✅ Live connection configured between HANA Cloud and SAC
- ✅ Database user permissions properly set
- ✅ Real-time dashboards and reports accessible in SAC

---

## Success Criteria

Upon completion of all tasks, the team will have:

1. **Automated data pipeline** from Jenkins → HANA Cloud (✅ Complete)
2. **Structured data model** optimized for analytics queries
3. **Real-time reporting capability** for performance regression analysis
4. **Self-service analytics** for stakeholders via SAP Analytics Cloud

## Next Steps

You should start with Task 1 (coordinating with Brian Chen) and proceed sequentially through the tasks, as each builds upon the previous one.
