"""Build a headless slice from the app's actual provider sources, not a protocol fork."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/com/feldman/scholix"
OUT = Path(__file__).parent / "providers"
NAMES = ["WebtopPlatform", "WebtopMailbox", "WebtopAttachmentDownload", "WebtopMoeLogin",
         "MashovPlatform", "MashovSms", "StudentsPortalPlatform", "ShahafPlatform", "ShahafApi",
         "ShahafMoeLogin", "BarIlanPlatform", "InbarPlatform", "InbarHttp", "InbarSchedule", "InbarSmsCode", "DemoPlatform"]


def without_android_download(text):
    # Only remove the Android file-opening method; headless file transfers have their own adapter.
    match = re.search(r"    override suspend fun downloadAttachment\([^\n]*\)(?:: Boolean)?\s*\{", text)
    if not match:
        return text
    start, index = match.start(), match.end()
    depth, state = 1, "code"
    while depth:
        char = text[index]
        if state == "string":
            if char == "\\":
                index += 1
            elif char == '"':
                state = "code"
        elif state == "comment":
            if char == "\n":
                state = "code"
        elif state == "block":
            if text[index:index+2] == "*/":
                state = "code"
                index += 1
        elif text[index:index+2] == "//":
            state = "comment"
        elif text[index:index+2] == "/*":
            state = "block"
        elif char == '"':
            state = "string"
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        index += 1
    return text[:start] + "    override suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean = error(\"Use the MCP attachment tool\")\n" + text[index:]


for relative in ["api/Platform.kt", "api/LoginFields.kt", "classroom/ClassroomApi.kt", "ui/components/MessageHtml.kt"] + [f"api/platforms/{name}.kt" for name in NAMES]:
    text = (SOURCE / relative).read_text(encoding="utf-8")
    text = without_android_download(text)
    text = text.replace('.put("captchaToken", null)', '.put("captchaToken", null as Any?)')
    text = re.sub(r"^import android\.(?:os.Environment|widget.Toast|content.Intent.createChooser)\n", "", text, flags=re.M)
    target = OUT / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
for name in ["WebtopMailboxTest", "WebtopAttachmentDownloadTest", "WebtopPasswordLoginTest", "InbarHttpTest", "InbarScheduleTest", "ShahafMoeLoginTest", "WebtopMoeLoginTest"]:
    target = OUT.parent / "providers-test" / (name + ".kt")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text((ROOT / "app/src/test/java/com/feldman/scholix/api/platforms" / (name + ".kt")).read_text(encoding="utf-8"), encoding="utf-8")
print("Prepared Scholix provider sources for the headless worker.")
