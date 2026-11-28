import { expect, test } from "nsh/test";
import { devErrorText, firmwareHintText } from "./headset";

test("invalid firmware codes become the family hint text", () => {
  expect(devErrorText("invalid_firmware:Zt")).toBe(`固件版本格式不对：${firmwareHintText("Zt")}`);
  expect(devErrorText("invalid_firmware:F8")).toBe(`固件版本格式不对：${firmwareHintText("F8")}`);
  expect(devErrorText("invalid_firmware:empty")).toBe("固件版本格式不对");
  expect(devErrorText("未知型号: x")).toBe("未知型号: x");
});
