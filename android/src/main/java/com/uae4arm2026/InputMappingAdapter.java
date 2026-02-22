package com.uae4arm2026;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * Adapter for displaying input mappings in a ListView.
 */
public class InputMappingAdapter extends BaseAdapter {

    private final Context context;
    private final List<InputMappingActivity.InputMapping> mappings;

    public InputMappingAdapter(Context context, List<InputMappingActivity.InputMapping> mappings) {
        this.context = context;
        this.mappings = mappings;
    }

    @Override
    public int getCount() {
        return mappings.size();
    }

    @Override
    public Object getItem(int position) {
        return mappings.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            
            holder = new ViewHolder();
            holder.text1 = convertView.findViewById(android.R.id.text1);
            holder.text2 = convertView.findViewById(android.R.id.text2);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        InputMappingActivity.InputMapping mapping = mappings.get(position);
        
        holder.text1.setText(mapping.getDisplayName());
        holder.text1.setTextSize(18);
        holder.text1.setTextColor(0xFF000000);
        
        holder.text2.setText("Tap to edit or delete");
        holder.text2.setTextSize(12);
        holder.text2.setTextColor(0xFF666666);

        return convertView;
    }

    private static class ViewHolder {
        TextView text1;
        TextView text2;
    }
}