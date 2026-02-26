package com.vendshop.aiadvent.data.model

import com.google.gson.annotations.SerializedName

data class ModelsListResponse(
    val `object`: String = "list",
    val data: List<ApiModel> = emptyList()
)

data class ApiModel(
    val id: String,
    val `object`: String = "model",
    @SerializedName("owned_by") val ownedBy: String? = null
)
