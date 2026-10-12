
export type Json = string | number | boolean | null | { [key: string]: Json | undefined } | Json[]

export type Database = {
  
  "graphql_public": {
          Tables: {
            [_ in never]: never
          }
          Views: {
            [_ in never]: never
          }
          Functions: {
            "graphql":
{ Args: { "extensions"?: Json,"operationName"?: string,"query"?: string,"variables"?: Json }; Returns: Json
                           }
          }
          Enums: {
            [_ in never]: never
          }
          CompositeTypes: {
            [_ in never]: never
          }
        },"public": {
          Tables: {
            "appointments": {
                  Row: {
                    "created_by": string | null,"datetime": string,"doctor_name": string,"id": string,"notes": string | null,"recipient_id": string
                  }
                  ComputedFields: never
                  Insert: {
                    "created_by"?: string | null,"datetime": string,"doctor_name": string,"id"?: string,"notes"?: string | null,"recipient_id": string
                  }
                  Update: {
                    "created_by"?: string | null,"datetime"?: string,"doctor_name"?: string,"id"?: string,"notes"?: string | null,"recipient_id"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "appointments_created_by_fkey"
      columns: ["created_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "appointments_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"care_circle_members": {
                  Row: {
                    "circle_id": string,"created_at": string,"role": Database["public"]['Enums']["member_role"],"user_id": string
                  }
                  ComputedFields: never
                  Insert: {
                    "circle_id": string,"created_at"?: string,"role"?: Database["public"]['Enums']["member_role"],"user_id": string
                  }
                  Update: {
                    "circle_id"?: string,"created_at"?: string,"role"?: Database["public"]['Enums']["member_role"],"user_id"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "care_circle_members_circle_id_fkey"
      columns: ["circle_id"]
isOneToOne: false
      referencedRelation: "care_circles"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "care_circle_members_user_id_fkey"
      columns: ["user_id"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"care_circles": {
                  Row: {
                    "created_at": string,"created_by": string | null,"id": string
                  }
                  ComputedFields: never
                  Insert: {
                    "created_at"?: string,"created_by"?: string | null,"id"?: string
                  }
                  Update: {
                    "created_at"?: string,"created_by"?: string | null,"id"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "care_circles_created_by_fkey"
      columns: ["created_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"care_recipients": {
                  Row: {
                    "allergies": (string)[],"blood_group": string | null,"caregiver_attested_at": string | null,"circle_id": string,"city": string | null,"conditions": (string)[],"consent_at": string | null,"created_at": string,"dob": string | null,"doctor_contacts": NonNullable<Json>,"emergency_contacts": NonNullable<Json>,"id": string,"language": string,"name": string,"opted_out_at": string | null,"relation": string,"timezone": string,"updated_at": string,"whatsapp_phone": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "allergies"?: (string)[],"blood_group"?: string | null,"caregiver_attested_at"?: string | null,"circle_id": string,"city"?: string | null,"conditions"?: (string)[],"consent_at"?: string | null,"created_at"?: string,"dob"?: string | null,"doctor_contacts"?: NonNullable<Json>,"emergency_contacts"?: NonNullable<Json>,"id"?: string,"language"?: string,"name": string,"opted_out_at"?: string | null,"relation"?: string,"timezone"?: string,"updated_at"?: string,"whatsapp_phone"?: string | null
                  }
                  Update: {
                    "allergies"?: (string)[],"blood_group"?: string | null,"caregiver_attested_at"?: string | null,"circle_id"?: string,"city"?: string | null,"conditions"?: (string)[],"consent_at"?: string | null,"created_at"?: string,"dob"?: string | null,"doctor_contacts"?: NonNullable<Json>,"emergency_contacts"?: NonNullable<Json>,"id"?: string,"language"?: string,"name"?: string,"opted_out_at"?: string | null,"relation"?: string,"timezone"?: string,"updated_at"?: string,"whatsapp_phone"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "care_recipients_circle_id_fkey"
      columns: ["circle_id"]
isOneToOne: false
      referencedRelation: "care_circles"
      referencedColumns: ["id"]
    }
                  ]
                },"checkins": {
                  Row: {
                    "date": string,"id": string,"recipient_id": string,"responded_at": string | null,"sent_at": string | null,"status": Database["public"]['Enums']["checkin_status"]
                  }
                  ComputedFields: never
                  Insert: {
                    "date": string,"id"?: string,"recipient_id": string,"responded_at"?: string | null,"sent_at"?: string | null,"status"?: Database["public"]['Enums']["checkin_status"]
                  }
                  Update: {
                    "date"?: string,"id"?: string,"recipient_id"?: string,"responded_at"?: string | null,"sent_at"?: string | null,"status"?: Database["public"]['Enums']["checkin_status"]
                  }
                  Relationships: [
                    {
      foreignKeyName: "checkins_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"dose_events": {
                  Row: {
                    "id": string,"responded_at": string | null,"schedule_id": string,"scheduled_at": string,"sent_at": string | null,"status": Database["public"]['Enums']["dose_status"],"whatsapp_message_id": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "id"?: string,"responded_at"?: string | null,"schedule_id": string,"scheduled_at": string,"sent_at"?: string | null,"status"?: Database["public"]['Enums']["dose_status"],"whatsapp_message_id"?: string | null
                  }
                  Update: {
                    "id"?: string,"responded_at"?: string | null,"schedule_id"?: string,"scheduled_at"?: string,"sent_at"?: string | null,"status"?: Database["public"]['Enums']["dose_status"],"whatsapp_message_id"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "dose_events_schedule_id_fkey"
      columns: ["schedule_id"]
isOneToOne: false
      referencedRelation: "medicine_schedules"
      referencedColumns: ["id"]
    }
                  ]
                },"emergency_links": {
                  Row: {
                    "active": boolean,"created_at": string,"id": string,"recipient_id": string,"token": string
                  }
                  ComputedFields: never
                  Insert: {
                    "active"?: boolean,"created_at"?: string,"id"?: string,"recipient_id": string,"token"?: string
                  }
                  Update: {
                    "active"?: boolean,"created_at"?: string,"id"?: string,"recipient_id"?: string,"token"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "emergency_links_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"medicine_schedules": {
                  Row: {
                    "created_at": string,"days_of_week": (number)[],"end_date": string | null,"id": string,"medicine_id": string,"start_date": string,"time_of_day": string
                  }
                  ComputedFields: never
                  Insert: {
                    "created_at"?: string,"days_of_week"?: (number)[],"end_date"?: string | null,"id"?: string,"medicine_id": string,"start_date"?: string,"time_of_day": string
                  }
                  Update: {
                    "created_at"?: string,"days_of_week"?: (number)[],"end_date"?: string | null,"id"?: string,"medicine_id"?: string,"start_date"?: string,"time_of_day"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "medicine_schedules_medicine_id_fkey"
      columns: ["medicine_id"]
isOneToOne: false
      referencedRelation: "medicines"
      referencedColumns: ["id"]
    }
                  ]
                },"medicines": {
                  Row: {
                    "active": boolean,"created_at": string,"dosage": string | null,"id": string,"instructions": string | null,"name": string,"photo_url": string | null,"recipient_id": string,"refill_threshold": number | null,"stock_count": number | null,"updated_at": string
                  }
                  ComputedFields: never
                  Insert: {
                    "active"?: boolean,"created_at"?: string,"dosage"?: string | null,"id"?: string,"instructions"?: string | null,"name": string,"photo_url"?: string | null,"recipient_id": string,"refill_threshold"?: number | null,"stock_count"?: number | null,"updated_at"?: string
                  }
                  Update: {
                    "active"?: boolean,"created_at"?: string,"dosage"?: string | null,"id"?: string,"instructions"?: string | null,"name"?: string,"photo_url"?: string | null,"recipient_id"?: string,"refill_threshold"?: number | null,"stock_count"?: number | null,"updated_at"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "medicines_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"notifications": {
                  Row: {
                    "created_at": string,"id": string,"payload": NonNullable<Json>,"read_at": string | null,"type": string,"user_id": string
                  }
                  ComputedFields: never
                  Insert: {
                    "created_at"?: string,"id"?: string,"payload"?: NonNullable<Json>,"read_at"?: string | null,"type": string,"user_id": string
                  }
                  Update: {
                    "created_at"?: string,"id"?: string,"payload"?: NonNullable<Json>,"read_at"?: string | null,"type"?: string,"user_id"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "notifications_user_id_fkey"
      columns: ["user_id"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"profiles": {
                  Row: {
                    "country": string | null,"created_at": string,"email": string | null,"id": string,"language": string,"name": string | null,"phone": string | null,"timezone": string,"updated_at": string
                  }
                  ComputedFields: never
                  Insert: {
                    "country"?: string | null,"created_at"?: string,"email"?: string | null,"id": string,"language"?: string,"name"?: string | null,"phone"?: string | null,"timezone"?: string,"updated_at"?: string
                  }
                  Update: {
                    "country"?: string | null,"created_at"?: string,"email"?: string | null,"id"?: string,"language"?: string,"name"?: string | null,"phone"?: string | null,"timezone"?: string,"updated_at"?: string
                  }
                  Relationships: [
                    
                  ]
                },"report_values": {
                  Row: {
                    "flag": Database["public"]['Enums']["value_flag"] | null,"id": string,"reference_range": string | null,"report_id": string,"test_name": string,"unit": string | null,"user_verified": boolean,"value": number
                  }
                  ComputedFields: never
                  Insert: {
                    "flag"?: Database["public"]['Enums']["value_flag"] | null,"id"?: string,"reference_range"?: string | null,"report_id": string,"test_name": string,"unit"?: string | null,"user_verified"?: boolean,"value": number
                  }
                  Update: {
                    "flag"?: Database["public"]['Enums']["value_flag"] | null,"id"?: string,"reference_range"?: string | null,"report_id"?: string,"test_name"?: string,"unit"?: string | null,"user_verified"?: boolean,"value"?: number
                  }
                  Relationships: [
                    {
      foreignKeyName: "report_values_report_id_fkey"
      columns: ["report_id"]
isOneToOne: false
      referencedRelation: "reports"
      referencedColumns: ["id"]
    }
                  ]
                },"reports": {
                  Row: {
                    "ai_status": Database["public"]['Enums']["report_ai_status"],"ai_summary": string | null,"created_at": string,"file_url": string,"id": string,"recipient_id": string,"report_date": string | null,"type": string | null,"uploaded_by": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "ai_status"?: Database["public"]['Enums']["report_ai_status"],"ai_summary"?: string | null,"created_at"?: string,"file_url": string,"id"?: string,"recipient_id": string,"report_date"?: string | null,"type"?: string | null,"uploaded_by"?: string | null
                  }
                  Update: {
                    "ai_status"?: Database["public"]['Enums']["report_ai_status"],"ai_summary"?: string | null,"created_at"?: string,"file_url"?: string,"id"?: string,"recipient_id"?: string,"report_date"?: string | null,"type"?: string | null,"uploaded_by"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "reports_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "reports_uploaded_by_fkey"
      columns: ["uploaded_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"whatsapp_events": {
                  Row: {
                    "id": string,"received_at": string
                  }
                  ComputedFields: never
                  Insert: {
                    "id": string,"received_at"?: string
                  }
                  Update: {
                    "id"?: string,"received_at"?: string
                  }
                  Relationships: [
                    
                  ]
                }
          }
          Views: {
            [_ in never]: never
          }
          Functions: {
            "can_access_medicine":
{ Args: { "p_medicine": string }; Returns: boolean
                           },
"can_access_recipient":
{ Args: { "p_recipient": string }; Returns: boolean
                           },
"can_access_report":
{ Args: { "p_report": string }; Returns: boolean
                           },
"can_access_schedule":
{ Args: { "p_schedule": string }; Returns: boolean
                           },
"create_care_recipient":
{ Args: { "p": Json }; Returns: string
                           },
"create_medicine":
{ Args: { "p_dosage": string,"p_end"?: string,"p_instructions": string,"p_name": string,"p_photo_url": string,"p_recipient": string,"p_refill_threshold": number,"p_start"?: string,"p_stock": number,"p_times": (string)[] }; Returns: string
                           },
"is_circle_member":
{ Args: { "p_circle": string }; Returns: boolean
                           },
"is_circle_owner":
{ Args: { "p_circle": string }; Returns: boolean
                           },
"storage_recipient_id":
{ Args: { "p_name": string }; Returns: string
                           }
          }
          Enums: {
            "checkin_status": "pending"|"sent"|"replied"|"missed","dose_status": "pending"|"sent"|"taken"|"missed","member_role": "owner"|"member","report_ai_status": "pending"|"processing"|"done"|"failed","value_flag": "normal"|"low"|"high"|"critical"
          }
          CompositeTypes: {
            [_ in never]: never
          }
        }
}

type DatabaseWithoutInternals = Omit<Database, '__InternalSupabase'>

type DefaultSchema = DatabaseWithoutInternals[Extract<keyof Database, "public">]

export type Tables<
  DefaultSchemaTableNameOrOptions extends
    | keyof (DefaultSchema["Tables"] & DefaultSchema["Views"])
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
        DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])
    : never = never
> = DefaultSchemaTableNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
      DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])[TableName] extends {
      Row: infer R
    }
    ? R
    : never
  : DefaultSchemaTableNameOrOptions extends keyof (DefaultSchema["Tables"] & DefaultSchema["Views"])
  ? (DefaultSchema["Tables"] & DefaultSchema["Views"])[DefaultSchemaTableNameOrOptions] extends {
      Row: infer R
    }
    ? R
    : never
  : never

export type TablesInsert<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never
> = DefaultSchemaTableNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Insert: infer I
    }
    ? I
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
  ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
      Insert: infer I
    }
    ? I
    : never
  : never

export type TablesUpdate<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never
> = DefaultSchemaTableNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Update: infer U
    }
    ? U
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
  ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
      Update: infer U
    }
    ? U
    : never
  : never

export type Enums<
  DefaultSchemaEnumNameOrOptions extends
    | keyof DefaultSchema["Enums"]
    | { schema: keyof DatabaseWithoutInternals },
  EnumName extends DefaultSchemaEnumNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"]
    : never = never
> = DefaultSchemaEnumNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"][EnumName]
  : DefaultSchemaEnumNameOrOptions extends keyof DefaultSchema["Enums"]
  ? DefaultSchema["Enums"][DefaultSchemaEnumNameOrOptions]
  : never

export type CompositeTypes<
  PublicCompositeTypeNameOrOptions extends
    | keyof DefaultSchema["CompositeTypes"]
    | { schema: keyof DatabaseWithoutInternals },
  CompositeTypeName extends PublicCompositeTypeNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"]
    : never = never
> = PublicCompositeTypeNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"][CompositeTypeName]
  : PublicCompositeTypeNameOrOptions extends keyof DefaultSchema["CompositeTypes"]
  ? DefaultSchema["CompositeTypes"][PublicCompositeTypeNameOrOptions]
  : never

export const Constants = {
  "graphql_public": {
          Enums: {
            
          }
        },"public": {
          Enums: {
            "checkin_status": ["pending", "sent", "replied", "missed"],"dose_status": ["pending", "sent", "taken", "missed"],"member_role": ["owner", "member"],"report_ai_status": ["pending", "processing", "done", "failed"],"value_flag": ["normal", "low", "high", "critical"]
          }
        }
} as const
