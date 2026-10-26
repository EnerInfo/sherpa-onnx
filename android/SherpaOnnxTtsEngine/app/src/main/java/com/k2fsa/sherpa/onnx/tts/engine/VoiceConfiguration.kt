data class VoiceConfiguration(
    val referenceSpeed: Float = 1.0f,
    val minimumSpeed: Float = 0.5f,
    val maximumSpeed: Float = 2.0f,
    val denoisingSteps: Int = 5,
    val pauseDuration: Float = 0.3f,
    val maximumChunkLength: Int = 300,
    val randomSeed: Int = -1
)
