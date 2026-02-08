# OmniLink Alexa Smart Home Skill (Lambda)

Java AWS Lambda function for an **Alexa Smart Home** skill that controls **HAI Omni IIe** units via the OmniLink API (using the `lib/omnilink.jar` library).

## Features

- **Discovery**: Reports all named **units** (lights/switches) from the Omni system so Alexa can discover them.
- **PowerController**: Turn units **on** (LEVEL_100) and **off** (LEVEL_0) via voice or the Alexa app.
- **ReportState**: Returns current on/off state when Alexa asks for device state.

## Build

Requirements: Java 21+, Maven 3.x.

The OmniLink API JAR is **provided in this project** under `lib/omnilink.jar`. No separate download is needed; the build compiles against it and packs it into the Lambda JAR.

```bash
mvn clean package
```

This produces:

- `target/omnilink-alexa-skill-1.0.0-lambda.jar` — fat JAR including `lib/omnilink.jar`, ready to upload to AWS Lambda.

## Lambda configuration

1. **Handler**: `com.omnilink.alexa.OmniLinkSmartHomeHandler::handleRequest`
2. **Runtime**: Java 21.
3. **Timeout**: 10–15 seconds (Omni connection can be slow).
4. **Environment variables** (required for Omni connection):

   | Variable           | Required | Description |
   |--------------------|----------|-------------|
   | `OMNI_HOST`        | Yes      | Omni controller IP (e.g. `192.168.1.3`) |
   | `OMNI_PORT`        | No       | Omni port (default `4369`) |
   | `OMNI_PRIVATE_KEY` | Yes      | Private key (e.g. `a0-b1-c2-d3-e4-f5-a6-b7-c8-d9-e0-f1-a2-b3-c4-d5`) |
   | `OMNI_LOGIN_CODE`  | No*      | Keypad login code for Omni-Link I (e.g. `1234`). Required when using `HAI_OMNI_LINK`. |
   | `OMNI_PROTOCOL`    | No       | `HAI_OMNI_LINK` (default) or `HAI_OMNI_LINK_II` |
   | `OMNI_SYSTEM_TYPE` | No       | `AEGIS_2000` (default) or `HAI_OMNI_IIE` for Omni IIe |

5. **Network**: Lambda must be able to reach the Omni controller (e.g. same VPC or Omni exposed via a reachable IP/port).

## Alexa Smart Home skill setup

1. In the [Alexa Developer Console](https://developer.amazon.com/alexa/console/ask), create a **Smart Home** skill (not Custom).
2. Under **Build** → **Interaction Model**, use the pre-built Smart Home model (no intents to define).
3. Under **Endpoint**, choose **AWS Lambda ARN** and paste your Lambda ARN.
4. (Optional) Configure **Account Linking** if you want to tie devices to user accounts; for a single controller, you can often skip it and use the same Lambda for all users.
5. Under **Permissions**, ensure the skill has access to the Lambda.

After publishing or testing, users say: *"Alexa, discover my devices"* to find Omni units, then *"Alexa, turn on [unit name]"* / *"turn off [unit name]"*.

## How it uses the OmniLink API

- **Connection**: `NetworkCommunication(SYSTEM_TYPE, IP, PORT, timeout, PRIVATE_KEY, PROTOCOL_TYPE)`, then `open()`.
- **Login** (Omni-Link I only): `execute(new LoginControl(loginCode))`.
- **Discovery**: `execute(new UploadNameMessageRequest())` → `UploadNameMessageReport.getInfoList()`, filter by `NameTypeEnum.UNIT`, use `getNumber()` and `getText()` for endpoint id and friendly name.
- **Turn on/off**: `execute(new UnitCommand(unitNumber, UnitControlEnum.LEVEL_100))` or `LEVEL_0`.
- **State**: `execute(new UnitStatusRequest(unitNumber))` → `UnitStatusReport.getInfoList().get(0).getValue()` (0 = off, non-zero = on).
- **Logout** (Omni-Link I): `execute(new LogoutControl())`, then `close()`.

## Project layout

```
omnilink-alexa-skill/
├── lib/
│   └── omnilink.jar          # OmniLink API (from net.homeip.mleclerc.omnilink)
├── src/main/java/com/omnilink/alexa/
│   ├── OmniLinkClient.java           # Omni connection, discovery, unit commands
│   └── OmniLinkSmartHomeHandler.java # Lambda entry, Alexa Discovery + PowerController
├── pom.xml
└── README.md
```

## How to allow the Alexa skill to invoke your Lambda

The skill must have **permission to invoke** your Lambda (resource-based policy). Use one of these methods.

### Method 1: From the Alexa Developer Console (easiest)

1. Open [Alexa Developer Console](https://developer.amazon.com/alexa/console/ask) → your skill.
2. Go to **Build** → **Endpoint** (in the left sidebar).
3. Under **Default region**, choose **North America** (or your region) and paste your Lambda ARN, e.g.  
   `arn:aws:lambda:us-east-1:123456789012:function:omnilink-alexa-skill`
4. Click **Save Endpoints**.
5. If the page shows **"Add permission to Lambda function"** or **"Allow Alexa to access your Lambda"**, click it. That adds the correct permission on the Lambda for your skill.
6. If you don’t see that button, use Method 2 or 3 below.

### Method 2: From the AWS Lambda console

1. Open **AWS Lambda** in the [AWS Console](https://console.aws.amazon.com/lambda/) → select your function.
2. Go to **Configuration** → **Permissions** (left side).
3. Scroll to **Resource-based policy statements**.
4. Click **Add permissions**.
5. Set:
   - **Policy statement:** e.g. `AllowAlexaSkillInvoke` (any name).
   - **Principal:** `alexa-appkit.amazon.com`
   - **Action:** `lambda:InvokeFunction`
   - **Event source token:** your **Skill ID** from the Alexa Developer Console, e.g.  
     `amzn1.ask.skill.abcdef12-3456-7890-abcd-ef1234567890`  
     (Find it: Alexa Developer Console → your skill → **Build** → **Endpoint**, or in the skill URL.)
6. Click **Save**.

**What the permission looks like (with condition):**  
When you use **Event source token**, Lambda creates a condition that restricts invocation to that skill. The policy statement looks like this:

```json
{
  "Sid": "AllowAlexaSkillInvoke",
  "Effect": "Allow",
  "Principal": {
    "Service": "alexa-appkit.amazon.com"
  },
  "Action": "lambda:InvokeFunction",
  "Resource": "arn:aws:lambda:us-east-1:123456789012:function:your-function-name",
  "Condition": {
    "StringEquals": {
      "lambda:EventSourceToken": "amzn1.ask.skill.abcdef12-3456-7890-abcd-ef1234567890"
    }
  }
}
```

Replace:
- `your-function-name` with your Lambda function name.
- `amzn1.ask.skill.abcdef12-3456-7890-abcd-ef1234567890` with your **Skill ID** (the same value you put in **Event source token**).

**If the console shows Source ARN instead of Event source token:** use your skill ARN: `arn:aws:alexa::VENDOR-ID:skill/SKILL-ID` (e.g. `arn:aws:alexa::123456789012:skill/amzn1.ask.skill.abc123...`). The condition will use `AWS:SourceArn` instead of `lambda:EventSourceToken`.

**Simpler variant (any Alexa skill):**  
- **Principal:** `alexa-appkit.amazon.com`  
- **Action:** `lambda:InvokeFunction`  
- **Event source token:** leave empty only if you understand the security impact. Prefer filling it with your skill ID so only your skill can invoke the Lambda.

### Method 3: AWS CLI

Replace `YOUR-FUNCTION-NAME`, `YOUR-REGION`, `YOUR-ACCOUNT-ID`, and `YOUR-SKILL-ID` with your values. Skill ID is in the Alexa Developer Console (e.g. in the skill URL or on the Endpoint page).

```bash
aws lambda add-permission \
  --function-name YOUR-FUNCTION-NAME \
  --statement-id AllowAlexaSkillInvoke \
  --action lambda:InvokeFunction \
  --principal alexa-appkit.amazon.com \
  --event-source-token amzn1.ask.skill.YOUR-SKILL-ID
```

Or using the skill ARN (if you have it):

```bash
aws lambda add-permission \
  --function-name YOUR-FUNCTION-NAME \
  --statement-id AllowAlexaSkillInvoke \
  --action lambda:InvokeFunction \
  --principal alexa-appkit.amazon.com \
  --source-arn arn:aws:alexa::YOUR-ACCOUNT-ID:skill/YOUR-SKILL-ID
```

**To find your Skill ID:** Alexa Developer Console → your skill → **Build** → **Endpoint**. The skill ID is often shown there or in the browser URL (e.g. `.../ask/console/skill/amzn1.ask.skill.xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).

---

## Lambda works in AWS Test but I can't enable the skill in the Alexa app

If your Lambda runs fine when you use **Test** in the AWS Lambda console but the skill won't enable (or discovery fails) when you enable it in the Alexa app, the problem is almost always one of these:

### 1. Alexa is not allowed to invoke your Lambda (most common)

Alexa must have **permission to invoke** your Lambda. Without it, when you enable the skill, Alexa gets **AccessDenied** and the skill can't call your function.

**Fix:**

- **Option A (recommended):** In [Alexa Developer Console](https://developer.amazon.com/alexa/console/ask) → your skill → **Build** → **Endpoint**. Set **Default region** to your Lambda ARN. When you save, the console may show **"Add permission to Lambda function"** or **"Allow Alexa to access your Lambda"** — click it so the skill ID is added as a trigger to your Lambda.
- **Option B (manual):** In **AWS Lambda** → your function → **Configuration** → **Permissions**. Under **Resource-based policy statements**, add a statement that allows **Alexa Skills Kit** (or your skill ID) to invoke the function. You can add the trigger from Lambda: **Configuration** → **Permissions** → **Add permissions** → **AWS account** (or use the Alexa Developer Console "Add permission" flow).

After adding the permission, try enabling the skill in the Alexa app again.

### 2. Skill endpoint not set or wrong region

In Alexa Developer Console → your skill → **Build** → **Endpoint**:

- **Default region** must be your Lambda ARN, e.g. `arn:aws:lambda:us-east-1:123456789012:function:your-function-name`.
- The Lambda **region** (e.g. `us-east-1`) must match. If your Lambda is in `us-east-1`, the endpoint must use that region.

### 3. Account linking blocking enable

If your skill has **Account Linking** set to required, the app may require the user to sign in before the skill is fully enabled. For a simple Smart Home skill without user accounts:

- In Alexa Developer Console → your skill → **Build** → **Account Linking**.
- Set to **No** (account linking not required) or **Optional**, unless you need it.

### 4. Wrong Amazon account in the Alexa app

For a **development** skill, the Alexa app must use the **same Amazon account** that owns the skill in the Developer Console. If you're signed in with a different account in the app, the skill may not appear under **Skills** or enabling may fail. Sign in to the app with your developer account, or add the other account as a **beta tester** for the skill (Developer Console → **Distribution** → **Beta Test**).

### 5. Skill not built as Smart Home

The skill must be created as **Smart Home** (not Custom). In the Developer Console, the skill type should be **Smart Home** and the **Endpoint** is the Lambda ARN. Custom skills use a different flow and won't show "Discover devices" when enabling.

---

## Troubleshooting: No logs in CloudWatch when enabling the skill

1. **Enabling the skill does not call Lambda by itself.** For Smart Home, Lambda runs only when Alexa sends a directive, for example when the user says *"Discover my devices"* or *"Turn on [device name]"*. After enabling the skill, say *"Discover my devices"* or use the Alexa app → **Devices** → **+** → **Add device** → your skill, then check CloudWatch again.

2. **Confirm the skill uses your Lambda.** In [Alexa Developer Console](https://developer.amazon.com/alexa/console/ask) → your skill → **Endpoint** (under Build). The **Default region** must be set to your Lambda’s ARN (e.g. `arn:aws:lambda:us-east-1:...:function:your-function-name`). If it’s empty or wrong, Alexa never calls your Lambda.

3. **Check Lambda’s execution role.** Lambda → your function → **Configuration** → **Permissions**. The role must allow CloudWatch Logs (e.g. `logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`). The default “Lambda basic execution” role includes this; custom roles may not.

4. **Check region.** CloudWatch log group is in the **same region** as the Lambda (e.g. **us-east-1**). In CloudWatch → **Log groups**, ensure you’re in that region and open `/aws/lambda/your-function-name`.

5. **Test Lambda directly.** In AWS Lambda console → your function → **Test** tab, create a test event with a sample Discover payload (see [Alexa Smart Home request format](https://developer.amazon.com/en-US/docs/alexa/smarthome/understand-the-smart-home-skill-api.html)), run Test, then look at **Monitor** → **View CloudWatch logs**. If logs appear here but not when using the app, the skill endpoint or invocation path from Alexa is wrong.

6. **Redeploy and wait.** After adding the “Lambda invoked” log and redeploying the JAR, trigger discovery or a command again; logs can take 10–30 seconds to show in CloudWatch.

## "Alexa could not find a new device to connect"

This message usually means Alexa sent a **Discover** request to your Lambda and got either an **error** or an **empty device list**. Check CloudWatch for your Lambda's log group and look for:

- **"Lambda invoked"** and **"Directive: Alexa.Discovery.Discover"** — Lambda received discovery.
- **"Discovery: connecting to Omni"** — Discovery started.
- **"Discovery: found N units"** — Omni returned N units (if N is 0, Alexa will say no devices found).
- **"Discovery: returning 0 endpoints"** — No units were returned (empty list).
- **"OmniLink error: ..."** or **"Error: ..."** — Exception (e.g. can't reach Omni, login failed, bad config).

**Common causes:**

1. **Lambda can't reach the Omni controller** — Lambda runs in AWS. If your Omni is on a home/office network, Lambda cannot reach it unless you use a tunnel (e.g. ngrok), put Lambda in a VPC with a path to Omni, or expose Omni (not recommended). For dev, run a tunnel to your Omni and set `OMNI_HOST` to the tunnel's public host.

2. **Wrong or missing environment variables** — In Lambda → Configuration → Environment variables, set at least `OMNI_HOST`, `OMNI_PRIVATE_KEY`, and (for Omni-Link I) `OMNI_LOGIN_CODE`. Wrong host/port/key or missing login code will cause connection or login failure and an error response.

3. **Omni returns no units** — Discovery uses **UploadNameMessageRequest** and only includes items with **NameTypeEnum.UNIT**. If your controller has no named units, the list is empty and Alexa says no devices found. Name your units in the Omni so they appear in the name report.

4. **Lambda timeout** — If connecting to Omni is slow or hangs, Lambda may time out. Increase the Lambda timeout (e.g. 15 s) and ensure the Omni is reachable and responding.

After fixing, redeploy the Lambda, say **"Discover my devices"** again (or re-enable the skill), and check CloudWatch for the new discovery logs.

## License

Use of `omnilink.jar` is subject to its own license. This Lambda code is provided as-is for use with the HAI Omni IIe and Alexa Smart Home.
