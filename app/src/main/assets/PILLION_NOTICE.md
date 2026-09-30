# Pillion provenance

Required Notice: Copyright 2026 the Pillion authors

Source: https://github.com/alexandrevega/pillion
Inspected/adapted commit: 6647af22f035ad74dc98f0e2a29e0c8a769a1caa
License: PolyForm Noncommercial 1.0.0 (see LICENSE.md)
https://polyformproject.org/licenses/noncommercial/1.0.0

NavFrame's navilite module adapts packet framing, CRC, authentication, setup values, RFCOMM service and image stop-and-wait sequencing from composeApp/src/commonMain/kotlin/app/pillion/{protocol,core} and composeApp/src/androidMain/kotlin/app/pillion/android/RfcommByteChannel.kt. Captured authentication test vectors are adapted from composeApp/src/commonTest/kotlin/app/pillion/ProtocolTest.kt. It adds bounded validated receiving, input validation, explicit device selection and timeout/cancellation handling. It does not reuse screen-capture functionality. See docs/NAVILITE_RESEARCH.md for the file-level inventory. Preserve this notice and license/URL when distributing these derived parts.
