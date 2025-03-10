/*
 Copyright (c) 2021 Tarek Mohamed Abdalla <tarekkma@gmail.com>

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3 of the License, or (at your option) any later
 version.

 This program is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 PARTICULAR PURPOSE. See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.export;

import com.ichi2.anki.dialogs.ExportCompleteDialog;
import com.ichi2.anki.dialogs.ExportDialog;
import com.ichi2.utils.ExtendedFragmentFactory;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;

class ExportDialogsFactory extends ExtendedFragmentFactory {

    @NonNull
    private final ExportCompleteDialog.ExportCompleteDialogListener mExportCompleteDialogListener;
    @NonNull
    private final ExportDialog.ExportDialogListener mExportDialogListener;


    public ExportDialogsFactory(
            @NonNull ExportCompleteDialog.ExportCompleteDialogListener exportCompleteDialogListener,
            @NonNull ExportDialog.ExportDialogListener exportDialogListener
    ) {
        this.mExportCompleteDialogListener = exportCompleteDialogListener;
        this.mExportDialogListener = exportDialogListener;
    }


    @NonNull
    @Override
    public Fragment instantiate(@NonNull ClassLoader classLoader, @NonNull String className) {
        Class<? extends Fragment> cls = loadFragmentClass(classLoader, className);

        if (cls == ExportDialog.class) {
            return newExportDialog();
        }

        if (cls == ExportCompleteDialog.class) {
            return newExportCompleteDialog();
        }

        return super.instantiate(classLoader, className);
    }

    @NonNull
    public ExportDialog newExportDialog() {
        return new ExportDialog(mExportDialogListener);
    }

    @NonNull
    public ExportCompleteDialog newExportCompleteDialog() {
        return new ExportCompleteDialog(mExportCompleteDialogListener);
    }
}
