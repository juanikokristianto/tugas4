package com.iammert.easymapslib.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.iammert.easymapslib.location.livedata.LocationData
import com.iammert.easymapslib.location.livedata.LocationLiveData
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.CameraUpdateFactory
import kotlinx.android.synthetic.main.activity_easy_maps.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import android.view.View
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.bottomsheet.BottomSheetBehavior.*
import com.iammert.easymapslib.R
import com.iammert.easymapslib.location.map.GoogleMapController
import com.iammert.easymapslib.location.places.SearchResultState.*
import com.iammert.easymapslib.ui.view.LocationMarkerView
import kotlinx.android.synthetic.main.activity_easy_maps.layoutBottomSheetForm
import kotlinx.android.synthetic.main.layout_address_form.*
import com.iammert.easymapslib.util.*
import com.iammert.easymapslib.data.SelectedAddressInfo
import kotlinx.android.synthetic.main.layout_address_form.view.*


class EasyMapsActivity : AppCompatActivity() {

    private lateinit var googleMapController: GoogleMapController

    private lateinit var locationLiveData: LocationLiveData

    private lateinit var easyMapsViewModel: EasyMapsViewModel

    private var isMapsInitialized = false

    private var bottomSheetState = STATE_COLLAPSED

    @SuppressLint("CheckResult")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_easy_maps)

        easyMapsViewModel = ViewModelProviders.of(this).get(EasyMapsViewModel::class.java)

        googleMapController = GoogleMapController()

        intent?.extras?.let {
            it.getParcelable<SelectedAddressInfo>(KEY_SELECTED_ADDRESS)?.let {
                easyMapsViewModel.initializeWithAddress(it)
                fillFormWithInitialValues(it)
            }

            easyMapsViewModel.validateFields(it.getBoolean(KEY_VALIDATE_FIELDS, true))
        }

        observeFormBottomSheeet()

        observeAddressSearch()

        observeToolbarActions()

        googleMapController.addIdleListener(GoogleMap.OnCameraIdleListener {
            googleMapController.getMap()?.let { map ->
                val markerPoint = locationMarkerView.getMarkerPoint()
                val markerLatLong = map.projection.fromScreenLocation(markerPoint)
                easyMapsViewModel.updateLatLong(markerLatLong)
            }
        })

        googleMapController.addClickListener(GoogleMap.OnMapClickListener {
            if (isExpanded()) {
                collapseBottomSheet()
            }
        })

        googleMapController.addIdleListener(locationMarkerView)
        googleMapController.addMoveStartListener(locationMarkerView)

        locationLiveData = LocationLiveData(this@EasyMapsActivity)

        locationLiveData.observe(this, Observer {
            when (it?.status) {
                LocationData.Status.PERMISSION_REQUIRED -> askLocationPermission(it.permissionList)
                LocationData.Status.ENABLE_SETTINGS -> enableLocationSettings(it.resolvableApiException)
                LocationData.Status.LOCATION_SUCCESS -> {
                    if (easyMapsViewModel.isInitializedWithAddress()) {
                        val currentAddressInfo = easyMapsViewModel.getSelectedAddressInfo()
                        updateUserLocation(
                            currentAddressInfo.address?.latitude,
                            currentAddressInfo.address?.longitude
                        )
                    } else {
                        updateUserLocation(it.location?.latitude, it.location?.longitude)
                    }
                }
            }
        })

        with(supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment) {
            findViewById<LocationMarkerView>(R.id.locationMarkerView)
                .initialize(
                    mapFragmentView = this.view,
                    bottomSheetExpandedHeight = R.dimen.size_form_full_height,
                    bottomSheetCollapsedHeight = R.dimen.size_form_peek_height
                )

            getMapAsync { map ->
                googleMapController.setGoogleMap(map)
                locationLiveData.start()
            }
        }

        easyMapsViewModel.getSelectedAddressViewStateLiveData().observe(this, Observer {
            editTextFullAddress.setText(it.getFullAddressText())
            textViewFullAddress.text = it.getFullAddressText()

            if (it.moveCameraToLatLong) {
                googleMapController.animateCamera(it.getLatitude(), it.getLongitude())
            }
        })

        easyMapsViewModel.getSearchQueryResultLiveData().observe(this, Observer {
            predictionResultView.setPredictions(it.searchResult)

            when (it.searchResultState) {
                LOADING -> {
                    imageViewSearch.visibility = View.GONE
                    progressBarSearchLoading.visibility = View.VISIBLE
                }
                COMPLETE -> {
                    imageViewSearch.visibility = View.VISIBLE
                    progressBarSearchLoading.visibility = View.GONE
                }
                ERROR -> {
                    imageViewSearch.visibility = View.VISIBLE
                    progressBarSearchLoading.visibility = View.GONE
                }
            }
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (PermissionUtils.isPermissionResultsGranted(grantResults)) {
            locationLiveData.start()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_LOCATION_SETTINGS && resultCode == Activity.RESULT_OK) {
            locationLiveData.start()
        }
    }

    override fun onBackPressed() {
        if (predictionResultView.isShowing()) {
            predictionResultView.hide()
            editTextSearchAddress.clearFocus()
            collapseBottomSheet()
            return
        }

        if (isExpanded()) {
            collapseBottomSheet()
            return
        }
        super.onBackPressed()
    }

    private fun askLocationPermission(permissionList: Array<String?>) {
        ActivityCompat.requestPermissions(this, permissionList, REQUEST_CODE_LOCATION_PERMISSION)
    }

    private fun enableLocationSettings(exception: ResolvableApiException?) {
        exception?.startResolutionForResult(this, REQUEST_CODE_LOCATION_SETTINGS)
    }

    @SuppressLint("MissingPermission")
    private fun updateUserLocation(latitude: Double?, longitude: Double?) {
        googleMapController.getMap()?.let { map ->
            if (isMapsInitialized.not()) {
                isMapsInitialized = true
                map.isMyLocationEnabled = true
                map.uiSettings.isMyLocationButtonEnabled = true
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(
                            latitude ?: 0.0,
                            longitude ?: 0.0
                        ), 14.0f
                    )
                )
            }

            easyMapsViewModel.updateLatLong(LatLng(latitude ?: 0.0, longitude ?: 0.0))
        }
    }

    private fun observeFormBottomSheeet() {
        val bottomSheetBehavior = from(layoutBottomSheetForm)

        bottomSheetBehavior.setBottomSheetCallback(object :
            BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                updateFormFieldVisibilityOnScroll(slideOffset)
                locationMarkerView.updateMarkerViewPosition(slideOffset)
            }

            @SuppressLint("SwitchIntDef")
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (bottomSheetState == newState) {
                    return
                }

                when (newState) {
                    STATE_EXPANDED -> {
                        googleMapController.disableGestures()
                        moveCameraOnBottomStateChanged(newState)
                        bottomSheetState = STATE_EXPANDED
                    }
                    STATE_COLLAPSED -> {
                        googleMapController.enableGestures()
                        bottomSheetState = STATE_COLLAPSED
                        moveCameraOnBottomStateChanged(newState)
                    }
                }
            }
        })

        layoutAddressCollapsed.setOnClickListener { bottomSheetBehavior.state = STATE_EXPANDED }

        layoutBottomSheetForm.buttonSave.setOnClickListener {
            collapseBottomSheet()
        }

        editTextBuildingNo.afterTextChanged {
            easyMapsViewModel.updateBuildingNumber(it)
            layoutBuildingNo.setBackgroundResource(R.drawable.border_field)
        }

        editTextDescription.afterTextChanged {
            easyMapsViewModel.updateDescription(it)
            layoutAddressDescription.setBackgroundResource(R.drawable.border_field)
        }

        editTextDoor.afterTextChanged {
            easyMapsViewModel.updateDoorNumber(it)
            layoutDoor.setBackgroundResource(R.drawable.border_field)
        }

        editTextAddressTitle.afterTextChanged {
            easyMapsViewModel.updateAddressTitle(it)
            layoutAddressTitle.setBackgroundResource(R.drawable.border_field)
        }
    }

    private fun observeAddressSearch() {
        editTextSearchAddress.afterTextChanged {
            imageViewClear.visibility = if (it.isNotEmpty()) View.VISIBLE else View.GONE
            easyMapsViewModel.searchAddress(it)
        }

        editTextSearchAddress.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && predictionResultView.isShowing().not()) {
                predictionResultView.show()
                hideBottomSheet()
            }
        }

        predictionResultView.setOnItemClicked {
            hideKeyboard()
            runAfter(500) {
                editTextSearchAddress.clearFocus()
                collapseBottomSheet()
                predictionResultView.hide()
                easyMapsViewModel.updateAutoCompletePrediction(it)
            }
        }

        imageViewClear.setOnClickListener { editTextSearchAddress.setText("") }
    }

    private fun observeToolbarActions() {
        imageViewArrowBack.setOnClickListener { finish() }

        buttonDone.setOnClickListener {
            if (validateForm()) {
                Intent()
                    .apply {
                        putExtra(
                            KEY_SELECTED_ADDRESS,
                            easyMapsViewModel.getSelectedAddressInfo()
                        )
                    }
                    .also { setResult(Activity.RESULT_OK, it) }
                    .also { finish() }
            } else {
                from(layoutBottomSheetForm).state = STATE_EXPANDED
            }
        }
    }

    private fun updateFormFieldVisibilityOnScroll(slideOffset: Float) {
        layoutAddressCollapsed.alpha = 1.0f - slideOffset
        layoutAddressFormExpanded.alpha = slideOffset

        when (slideOffset) {
            0f -> {
                layoutAddressCollapsed.visibility = View.VISIBLE
                layoutAddressFormExpanded.visibility = View.INVISIBLE
            }
            1f -> {
                layoutAddressCollapsed.visibility = View.INVISIBLE
                layoutAddressFormExpanded.visibility = View.VISIBLE
            }
            else -> {
                layoutAddressCollapsed.visibility = View.VISIBLE
                layoutAddressFormExpanded.visibility = View.VISIBLE
            }
        }
    }

    private fun moveCameraOnBottomStateChanged(bottomSheetState: Int) {
        val point = when (bottomSheetState) {
            STATE_COLLAPSED -> locationMarkerView.getCollapsedCameraPoint()
            STATE_EXPANDED -> locationMarkerView.getExpandedCameraPoint()
            else -> null
        }

        point?.let {
            googleMapController
                .animateCameraToPoint(
                    screenPoint = point,
                    onStarted = { locationMarkerView.setFloatingOnMove(false) },
                    onFinished = { locationMarkerView.setFloatingOnMove(true) })
        }
    }

    private fun collapseBottomSheet() {
        val formBehavior = from(layoutBottomSheetForm)
        formBehavior.isHideable = false
        formBehavior.state = STATE_COLLAPSED
    }

    private fun hideBottomSheet() {
        val formBehavior = from(layoutBottomSheetForm)
        formBehavior.isHideable = true
        formBehavior.state = STATE_HIDDEN
    }

    private fun isExpanded() = from(layoutBottomSheetForm).state == STATE_EXPANDED

    private fun fillFormWithInitialValues(selectedAddressInfo: SelectedAddressInfo) {
        with(selectedAddressInfo) {
            textViewFullAddress.text = fullAddress
            editTextFullAddress.setText(fullAddress)
            editTextBuildingNo.setText(buildingNumber)
            editTextDescription.setText(description)
            editTextDoor.setText(door)
            editTextAddressTitle.setText(addressTitle)
        }
    }

    private fun validateForm(): Boolean {
        if (easyMapsViewModel.isValidateNeed().not()) {
            return true
        }

        var isValidationOK = true

        if (editTextBuildingNo.text.isNullOrBlank()) {
            layoutBuildingNo.setBackgroundResource(R.drawable.border_field_required)
            isValidationOK = false
        }

        if (editTextAddressTitle.text.isNullOrBlank()) {
            layoutAddressTitle.setBackgroundResource(R.drawable.border_field_required)
            isValidationOK = false
        }

        if (editTextDoor.text.isNullOrBlank()) {
            layoutDoor.setBackgroundResource(R.drawable.border_field_required)
            isValidationOK = false
        }

        return isValidationOK
    }

    companion object {

        const val KEY_SELECTED_ADDRESS = "KEY_SELECTED_ADDRESS"
        const val KEY_VALIDATE_FIELDS = "KEY_VALIDATE_FIELDS"

        const val REQUEST_CODE_LOCATION_PERMISSION = 12
        const val REQUEST_CODE_LOCATION_SETTINGS = 13

        @JvmStatic
        fun newIntent(
            context: Context,
            selectedAddressInfo: SelectedAddressInfo? = null,
            validateFields: Boolean
        ): Intent {
            return Intent(context, EasyMapsActivity::class.java)
                .apply {
                    putExtra(KEY_SELECTED_ADDRESS, selectedAddressInfo)
                    putExtra(KEY_VALIDATE_FIELDS, validateFields)
                }
        }
    }
}