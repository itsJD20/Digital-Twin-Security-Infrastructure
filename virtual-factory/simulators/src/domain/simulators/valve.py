from datetime import datetime
import requests
from src.sign import sign_json_payload
from src.domain.simulators.pipe import Pipe


class Valve(Pipe):

    def __init__(self, params):
        super().__init__(params)
        self._open = params.get("open", True)
        self.ditto_openplc_thing_id = params["ditto_openplc_thing_id"] 
        self.ditto_hmi_thing_id = params["ditto_hmi_thing_id"] #add line for HMI
        self.name = params["name"]

        if not self._open:
            self.close()
        else:
            self.open()

    def open(self):
        self._open = True
        self.input_blocked = False
        self.output_blocked = False

        status = 0
        if self.ditto_openplc_thing_id:
            payload = {"open": self._open}
            payload["timestamp"] = datetime.now().isoformat()
            payload["signature"] = sign_json_payload(payload)
            res = requests.put(
                (
                    f"http://host.docker.internal:8080/api/2/things/{self.ditto_openplc_thing_id}"
                    f"/features/{self.name}"
                ),
                json= {"properties": payload}, # add this line to update the open property
                headers={"Authorization": "Basic ZGl0dG86ZGl0dG8="}
            )
            status = res.status_code
            print(res.status_code, "\n", res.url)

        if __debug__:
            print("Válvula: {} - open {} - status: {}".format(self._name, self._open, status))

    def close(self):
        self._open = False
        self.input_blocked = True
        self.output_blocked = True

        status = 0
        if self.ditto_openplc_thing_id:
            payload = {"open": self._open}
            payload["timestamp"] = datetime.now().isoformat()
            payload["signature"] = sign_json_payload(payload)
            res = requests.put(
                (
                    f"http://host.docker.internal:8080/api/2/things/{self.ditto_openplc_thing_id}"
                    f"/features/{self.name}"
                ),
                json= {"properties": payload}, # add this line to update the open property
                headers={"Authorization": "Basic ZGl0dG86ZGl0dG8="}
            )

            status = res.status_code
            print(res.status_code, "\n", res.url)

        if __debug__:
            print("Válvula: {} - open {} - status: {}".format(self._name, self._open, status))

    def internal_run(self):
        info = self.read_industrial_info(self.get_read_info_for_industry())
        value_open = info.get("open")
        if value_open is not None:
            if value_open:
                self.open()
            else:
                self.close()
        info = self.write_industrial_info(self.get_write_info_for_industry())
        super().internal_run()
    

        if __debug__:
            print("Válvula: {} - open {}, current_input: {}, current_output: {}".format(
                self._name, self._open,
                self.current_input_level,
                self.current_output_level
                )
            )

    def get_read_info_for_industry(self) -> list:
        return ["open"]

    def get_write_info_for_industry(self) -> dict:
        return {"open": self._open}
