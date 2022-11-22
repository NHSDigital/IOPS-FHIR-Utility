mvn clean install

docker build -t fhir-utility .

docker tag fhir-utility:latest 365027538941.dkr.ecr.eu-west-2.amazonaws.com/fhir-utility:latest
docker tag fhir-utility:latest 365027538941.dkr.ecr.eu-west-2.amazonaws.com/fhir-utility:1.0.4

docker push 365027538941.dkr.ecr.eu-west-2.amazonaws.com/fhir-utility:latest

docker push 365027538941.dkr.ecr.eu-west-2.amazonaws.com/fhir-utility:1.0.4
