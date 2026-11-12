package th.ogay.luma.ocr

/** Base class for failures the caller can explain to the user. Messages are Thai and ready to show. */
open class ThaiOcrException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A model file is missing, damaged or cannot be loaded by OpenCV. Reinstalling the app restores it. */
class ThaiOcrModelException(message: String, cause: Throwable? = null) : ThaiOcrException(message, cause)

/** The caller's cancel check returned true, or the thread was interrupted. */
class ThaiOcrCancelledException : ThaiOcrException("ยกเลิกการอ่านข้อความแล้ว")

/** Recognition ran past the caller's time limit. Work stops at the next text line. */
class ThaiOcrTimeoutException(val timeoutMs: Long) :
    ThaiOcrException("อ่านข้อความนานเกิน ${(timeoutMs + 999) / 1000} วินาที ลองครอปให้เหลือเฉพาะส่วนที่มีข้อความ")
