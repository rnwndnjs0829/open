package com.mapzen.widget;

import com.mapzen.util.Logger;

import android.content.Context;
import android.preference.EditTextPreference;
import android.util.AttributeSet;

public class EditIntPreference extends EditTextPreference {

    public EditIntPreference(Context context) {
        super(context);
    }

    public EditIntPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EditIntPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected String getPersistedString(String defaultReturnValue) {
        return String.valueOf(getPersistedInt(-1));
    }

    @Override
    protected boolean persistString(String value) {
        int intValue;
        try {
            intValue = Integer.valueOf(value);
        } catch (NumberFormatException e) {
            Logger.e("Unable to parse preference value: " + value);
            return true;
        }

        return persistInt(intValue);
    }
}
