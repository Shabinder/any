package any.ui.service.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import any.base.file.AndroidFileReader
import any.base.file.FileReader
import any.base.result.ValidationResult
import any.data.entity.ServiceConfig
import any.data.entity.ServiceConfigValue
import any.data.js.ServiceRunner
import any.data.js.validator.BasicServiceConfigsValidator
import any.data.js.validator.JsServiceConfigsValidator
import any.data.repository.ServiceRepository
import any.domain.entity.UiServiceManifest
import any.domain.service.toUiManifest
import any.richtext.html.DefaultHtmlParser
import any.richtext.html.HtmlParser
import com.vdurmont.semver4j.Semver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServiceViewModel(
    private val serviceRepository: ServiceRepository,
    private val appRunner: ServiceRunner,
    private val fileReader: FileReader,
    private val htmlParser: HtmlParser = DefaultHtmlParser(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _serviceUiState = MutableStateFlow(ServiceUiState())
    val serviceUiState = _serviceUiState

    fun checkUpgradeInfo(service: UiServiceManifest) {
        viewModelScope.launch(workerDispatcher) {
            val local = serviceRepository.findDbService(service.id)
            if (local == null) {
                _serviceUiState.update {
                    it.copy(upgradeInfo = null)
                }
                return@launch
            }

            val localVer = Semver(local.version)
            val currVer = Semver(service.version)
            val isUpgrade = currVer != localVer ||
                    local.main != service.main ||
                    local.mainChecksums != service.mainChecksums ||
                    local.configs != service.configs
            val upgradeInfo = if (isUpgrade) {
                UpgradeInfo(
                    fromVersion = local.version,
                    toVersion = service.version,
                )
            } else {
                null
            }
            _serviceUiState.update {
                it.copy(upgradeInfo = upgradeInfo)
            }
        }
    }

    fun tryValidateConfigsAndSave(
        service: UiServiceManifest,
        values: Map<String, ServiceConfigValue?>,
        runJsValidator: Boolean,
    ) = viewModelScope.launch(workerDispatcher) {
        val serviceConfigs = service.configs
        if (serviceConfigs.isNullOrEmpty()) {
            // Nothing to validate
            _serviceUiState.update {
                it.copy(areAllValidationsPassed = true)
            }
            saveService(service)
            return@launch
        }

        _serviceUiState.update { it.copy(isValidating = true) }

        val configs = serviceConfigs.map { it.copy(value = values[it.key] ?: it.value) }

        // Basic validations
        val basicResults = BasicServiceConfigsValidator.validate(configs)

        val jsValidator = JsServiceConfigsValidator(serviceRunner = appRunner, service = service.raw)

        val jsValidatorResults = if (runJsValidator &&
            basicResults.all { it is ValidationResult.Pass }
        ) {
            // Run js service config validations
            jsValidator.validate(configs)
        } else {
            MutableList(basicResults.size) { ValidationResult.Pass }
        }

        // Merge validations
        val results = MutableList(basicResults.size) {
            when {
                basicResults[it] is ValidationResult.Fail -> {
                    basicResults[it]
                }

                jsValidatorResults[it] is ValidationResult.Fail -> {
                    jsValidatorResults[it]
                }

                else -> {
                    ValidationResult.Pass
                }
            }
        }

        val validations = mutableMapOf<String, ValidationResult>()
        for (i in configs.indices) {
            validations[configs[i].key] = results[i]
        }

        val areAllValidationsPassed = results.all { it is ValidationResult.Pass }
        val updatedRawService = if (areAllValidationsPassed) {
            val updatedService = jsValidator.getUpdatedService()
            val updatedConfigs = updatedService.configs?.map {
                it.copy(value = values[it.key] ?: it.value)
            }
            updatedService.copy(configs = updatedConfigs)
        } else {
            service.raw
        }
        val updatedService = updatedRawService.toUiManifest(fileReader, htmlParser)

        if (areAllValidationsPassed) {
            // All passed, save the service
            saveService(updatedService)
        }

        _serviceUiState.update {
            it.copy(
                isValidating = false,
                areAllValidationsPassed = areAllValidationsPassed,
                validations = validations,
                updatedService = updatedService,
            )
        }
    }

    private suspend fun saveService(service: UiServiceManifest) {
        val toSave = service.toStored()
        serviceRepository.upsertDbService(toSave.raw)
        _serviceUiState.update { it.copy(savedService = toSave) }
    }

    fun clearValidationResult(config: ServiceConfig) = viewModelScope.launch(workerDispatcher) {
        val validations = _serviceUiState.value.validations
        if (!validations.containsKey(config.key)) {
            return@launch
        }
        val updated = validations.toMutableMap()
        updated.remove(config.key)
        _serviceUiState.update {
            it.copy(validations = updated.toMap())
        }
    }

    fun resetUiState() {
        _serviceUiState.update { ServiceUiState() }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("unchecked_cast")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ServiceViewModel(
                serviceRepository = ServiceRepository.getDefault(context),
                appRunner = ServiceRunner.getDefault(context),
                fileReader = AndroidFileReader(context),
            ) as T
        }
    }
}
