package com.feldman.scholix.courses

import com.feldman.scholix.AppDest
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform

val providerDependentPages = setOf(AppDest.Grades, AppDest.Schedule, AppDest.Attendance, AppDest.Homework, AppDest.Messages, AppDest.Materials)

fun supportedProviderPages(provider: Platform?, lemida: Boolean = false): List<AppDest> = listOfNotNull(
    AppDest.Grades.takeIf { provider?.suportsGrades == true },
    AppDest.Schedule.takeIf { provider?.supportsSchedule == true },
    AppDest.Attendance.takeIf { provider?.supportsAttendance == true },
    AppDest.Homework.takeIf { lemida || provider is OpenAUPlatform || provider is GoogleClassroomPlatform },
    AppDest.Messages.takeIf { provider is WebtopPlatform || provider is GoogleClassroomPlatform },
    AppDest.Materials.takeIf { lemida || provider is OpenAUPlatform }
)
