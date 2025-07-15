from abc import abstractmethod
from datetime import datetime
from src.sign import sign_json_payload
import requests

from src.domain.isimulatorconnectorclient import ISimulatorConnectorClient


class Notifier:

    def __init__(self, params: dict):
        self._name = params.get("name")
        self._simulator_connector_client = self.__init_simulator_connector_singleton(
            params.get("simulator_connector_client"))

    def __init_simulator_connector_singleton(self, simulator_client: ISimulatorConnectorClient):
        try:
            self._simulator_connector_client
        except AttributeError:
            self._simulator_connector_client = simulator_client

        return self._simulator_connector_client

    def get_name(self) -> str:
        return self._name

    @abstractmethod
    def get_msg_to_notify(self):
        raise NotImplemented

    def notify(self):
        msg = self.get_msg_to_notify() # this variable stores the data to be updated to the HMI
        self._simulator_connector_client.send(self._name, msg)
        if hasattr(self,"ditto_hmi_thing_id") and self.ditto_hmi_thing_id:
            msg["timestamp"] = datetime.now().isoformat()
            msg["signature"] = sign_json_payload(msg) #sign the message to be sent to the HMI
            res = requests.put( 
                (
                    f"http://host.docker.internal:8080/api/2/things/{self.ditto_hmi_thing_id}"
                    f"/features/{self._name}"
                ), #this is the Ditto API getting called to update the feature in the HMI thing in Ditto
                json= {"properties": msg}, 
                headers={"Authorization": "Basic ZGl0dG86ZGl0dG8="}
            )

            status = res.status_code
            print(res.status_code, "\n", res.url)


    def subscribe(self, name: str, callback):
        self._simulator_connector_client.subscribe(name, callback)
