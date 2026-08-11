
create schema healthdata;

CREATE TABLE healthdata.customer (
    customer_id int GENERATED ALWAYS AS IDENTITY,
    uuid uuid,
    name varchar(255) NULL,
    bucket varchar(255) NULL, -- HDC-19: renamed from incoming_bucket
    incoming_request_location varchar(255) NULL,
    request_processed_location varchar(255) NULL, -- HDC-33: renamed from processed_location
    error_location varchar(255) NULL,
    active boolean NULL,
    CONSTRAINT xpk_customer PRIMARY KEY (customer_id));

CREATE TABLE healthdata.file (
    file_id int GENERATED ALWAYS AS IDENTITY,
    customer_id int,
    name varchar(255) NULL,
    source varchar(255) NULL,
    source_uuid uuid NULL,
    reference_number varchar(255) NULL,
    status varchar(255) NULL,
    created_on timestamptz NULL,
    CONSTRAINT xpk_file PRIMARY KEY (file_id));


CREATE TABLE healthdata.panel (
    panel_id int GENERATED ALWAYS AS IDENTITY,
    customer_id int not null,
    reference_number varchar(255) NULL,
    status varchar(255) NULL,
    created_on timestamptz NULL,
    completed_on timestamptz NULL,
    lookback int,
    data_source varchar(255),
    start_date date,
    end_date date,
    product_id int,
    -- HDC-25: filename of the sent request file generated for this panel
    sent_request_filename varchar(255) NULL,
    last_updated timestamptz NULL,
    CONSTRAINT xpk_panel PRIMARY KEY (panel_id));

CREATE TABLE healthdata.patient (
	patient_id int GENERATED ALWAYS AS IDENTITY,
	panel_id int NOT NULL,
	sequence_number int NOT NULL,
	mrn varchar(255) NOT NULL,
	last_name varchar(255) NOT NULL,
	first_name varchar(255) NOT NULL,
	middle_name varchar(255) NULL,
	prefix varchar(255) NULL,
	suffix varchar(255) NULL,
	address_line_1 varchar(255) NULL,
	address_line_2 varchar(255) NULL,
	city varchar(255) NULL,
	state varchar(255) NULL,
	postal_code varchar(255) NOT NULL,
	home_phone varchar(255) NULL,
	alt_phone varchar(255) NULL,
	date_of_birth date NOT NULL,
	gender varchar(255) NOT NULL,
	physician_npi varchar(255) NOT NULL,
    physician_name varchar(255) NOT NULL,
	consent varchar(255) NOT NULL,
	CONSTRAINT PK_patient PRIMARY KEY (patient_id));

CREATE TABLE healthdata.product (
    product_id int GENERATED ALWAYS AS IDENTITY,
    code varchar(255) NULL,
    name varchar(255) NULL,
    description varchar(255) NULL,
    effective_date date NULL,
    inactive_date date NULL,
    file_config json NULL,
    CONSTRAINT xpk_product PRIMARY KEY (product_id));

CREATE TABLE healthdata.ss_patient_response (
    ss_patient_response_id int GENERATED ALWAYS AS IDENTITY,
    file_name varchar(255) NOT NULL,
    processed_on timestamptz NOT NULL,
    shd_version varchar(255) NOT NULL,
    shd_receiver_id varchar(255) NOT NULL,
    shd_sender_id varchar(255) NOT NULL,
    shd_transaction_control_number varchar(255) NOT NULL,
    shd_transaction_date varchar(255) NOT NULL,
    shd_transaction_time varchar(255) NOT NULL,
    shd_transaction_file_type varchar(255) NULL,
    shd_transmission_control_number varchar(255) NOT NULL,
    shd_transmission_date varchar(255) NOT NULL,
    shd_transmission_time varchar(255) NOT NULL,
    shd_file_type varchar(255) NOT NULL,
    shd_load_status varchar(255) NOT NULL,
    shd_load_status_description varchar(255) NOT NULL,
    str_processed_record_count int NOT NULL,
    str_error_record_count int NOT NULL,
    str_loaded_record_count int NOT NULL,
    str_total_error_count int NOT NULL,
    CONSTRAINT PK_ss_patient_response PRIMARY KEY (ss_patient_response_id)
    );


CREATE TABLE healthdata.ss_patient_response_detail (
    ss_patient_response_detail_id int GENERATED ALWAYS AS IDENTITY,
    ss_patient_response_id int NOT NULL,
    record_sequence_number int NULL,
    source_record_sequence_number int NULL,
    assigning_authority  varchar(255)  NULL,
    patient_id  varchar(255)  NULL,
    error_type varchar(255)  NULL,
    error_code varchar(255)  NULL,
    error_description  varchar(255)  NULL,
    CONSTRAINT PK_ss_patient_response_detail PRIMARY KEY (ss_patient_response_detail_id));

CREATE TABLE healthdata.ss_rx_history_response (
   ss_rx_history_response_id int GENERATED ALWAYS AS IDENTITY,
   reference_number varchar(10) NOT NULL,
   total_count int NOT NULL,
   patient_count int NOT NULL,
   ok_count int NOT NULL,
   multiple_response_count int NOT NULL,
   empty_count int NOT NULL,
   not_found_count int NOT NULL,
   incomplete_count int NOT NULL,
   error_count int NOT NULL,
   unknown_count int NOT NULL,
   inserted_on timestamptz NOT NULL,
   updated_on timestamptz NULL,
   CONSTRAINT PK_ss_rx_history_response PRIMARY KEY (ss_rx_history_response_id)
);


alter table hcx.healthdata.customer 
add column outgoing_request_location varchar(255);

alter table hcx.healthdata.customer 
add column request_sent_location varchar(255);

ALTER TABLE hcx.healthdata.customer ADD COLUMN s3_bucket VARCHAR(255);

ALTER TABLE hcx.healthdata.panel ADD COLUMN product_id int NULL;
ALTER TABLE hcx.healthdata.panel ADD COLUMN last_updated timestamptz NULL;
ALTER TABLE hcx.healthdata.panel ADD COLUMN sent_request_filename varchar(255) NULL;

insert into hcx.healthdata.product 
(code,name,description,file_config)
values ('rxhist','RX History', 'Medication History Service', '{
"HDR": {
    "RecordType": "HDR",
    "Version": "3.0",
    "SenderID": "T00000000022665",
    "Password": "SFTEB2ZLLR",
    "ReceiverID": "S00000000000003",
    "transmissionControlNumber": "panel.referenceNumber",
    "TransmissionDate": "Current date in YYYYMMDD",
    "TransmissionTime": "Current time in HHMMSSDD",
    "PatientLoadFileType": "PMA",
    "FileCreationDate": "panel.created_on in YYYYMMDD",
    "usageIndicator": "T",
    "PopulationID": "panel.referenceNumber",
    "LookbackMonths": "panel.lookback",
    "DataSource": "",
    "RequestStartDate": "panel.start_date in YYYYMMDD",
    "RequestEndDate": "panel.end_date in YYYYMMDD"
  },
  "DTL": {
    "RecordType": "PNM",
    "RecordSequenceNumber": "patient.sequence_number",
    "AssigningAuthority": "1.3.44444.666.3.2.1",
    "PatientID": "patient.mrn",
    "LastName": "patient.last_name",
    "FirstName": "patient.first_name",
    "MiddleName": "patient.middle_name",
    "Prefix": "patient.prefix",
    "Suffix": "patient.suffix",
    "AddressLine1": "patient.address_line_1",
    "AddressLine2": "patient.address_line_2",
    "City": "patient.city",
    "State": "patient.state",
    "ZipCode": "patient.postal_code",
    "DateOfBirth": "patient.date_of_birth in CCYYMMDD",
    "Gender": "patient.gender",
    "NPI": "patient.physician_npi",
    "EndMonitoringDate": "",
    "NotificationTypes": "",
    "PrimaryTelephone": "patient.home_phone"
  },
  "TRL": {
    "RecordType": "TRL",
    "TotalDetailRecords": "max patient.sequence_number for panel_id"
  }
}');


insert into healthdata.customer 
(uuid,name,bucket,incoming_request_location,request_processed_location,error_location,active,outgoing_request_location,request_sent_location, s3_bucket)
values ('f47ac10b-58cc-4372-a567-0e02b2c3d479','Customer 01','cust01','incoming','processed','errors',true,'outgoing-requests','sent-requests','hcx-intake-qa')

