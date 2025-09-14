package com.feldman.scholix.data

class Account(
    private var _username: String,
    private var _password: String,
    private var _source: String = "Classroom", // Default
    private var _name: String
) {
    private var _isEditing: Boolean = false
    private var _year: Int = 0   // Only relevant for Bar Ilan (1, 2, 3)
    private var _location: Int = 0 // 0 - main, 1 - secondary...

    fun getUsername(): String = _username
    fun getPassword(): String = _password
    fun getName(): String = _name
    fun getSource(): String = _source
    fun isEditing(): Boolean = _isEditing
    fun getYear(): Int = _year

    fun setUsername(username: String) { this._username = username }
    fun setPassword(password: String) { this._password = password }
    fun setName(name: String) { this._name = name }
    fun setSource(source: String) { this._source = source }
    fun setEditing(editing: Boolean) { this._isEditing = editing }
    fun setYear(year: Int) { this._year = year }

    fun isMain(): Boolean = _location == 0

    fun setMain(main: Boolean) { _location = 0 }

    fun setLocation(location: Int) { this._location = location }
}
