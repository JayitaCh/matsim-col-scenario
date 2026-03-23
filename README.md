# MATSim Scenario: City of London

## Overview

This project contains the setup and generation of a MATSim scenario for the City of London. The goal is to create a realistic, data-driven transport simulation environment that can be used for analysis, experimentation, and planning.

The scenario integrates demographic and geographic datasets to model travel demand and transport infrastructure within the City of London.

---

## Data Sources

### 1. Census Data

Population and socio-demographic characteristics are derived from official census datasets. These data are used to:

* Generate synthetic populations
* Define activity patterns focusing on the Work location based on the OD Flow data from NOMIS

### 2. OpenStreetMap (OSM)

The transport network is based on data from OpenStreetMap. This includes:

* Road networks
* Transport infrastructure
* Geographic layout of the City of London

The OSM data is processed and converted into a MATSim-compatible network format.

---

## Scenario Generation

The workflow for generating the scenario typically involves:

1. Extracting and preprocessing OpenStreetMap data
2. Building the MATSim network
3. Processing census data to create a synthetic population
4. Generating travel demand and activity plans
5. Running initial simulations for validation

---

## Requirements

* Java (compatible with MATSim)
* MATSim framework
* Additional dependencies as specified in the project

---

## Usage

1. Prepare required datasets (Census and OpenStreetMap)
2. Place them in the local `data/` directory
3. Run the scenario generation scripts
4. Execute the MATSim simulation using the provided configuration

---

## Notes

* Large input datasets are excluded from version control and must be obtained separately.
* Ensure that all data usage complies with the respective licensing terms.

---

## Acknowledgements

* Census data providers
* OpenStreetMap contributors
* MATSim community

## Still under Development
