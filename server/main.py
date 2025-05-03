from fastapi import FastAPI, Form, HTTPException, Response, Query
import sqlite3
import os
import uuid
from typing import List
import bcrypt
import time

app = FastAPI()
DATABASE_FILE = "db.sqlite"

# Database initialization
def initialize_database():
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    # User table
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS User (
        user_id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        public_key TEXT NOT NULL,
        token TEXT NOT NULL
    )
    """)
    
    # Message table
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS Message (
        message_id INTEGER PRIMARY KEY AUTOINCREMENT,
        recipient_id TEXT NOT NULL,
        sender_id TEXT NOT NULL,
        channel_id TEXT NOT NULL,
        content TEXT NOT NULL,
        timestamp INTEGER NOT NULL,
        is_group_channel BOOLEAN NOT NULL,
        FOREIGN KEY (recipient_id) REFERENCES User(user_id),
        FOREIGN KEY (sender_id) REFERENCES User(user_id)
    )
    """)
    
    # Group tables
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS [Group] (
        group_id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        creator_id TEXT NOT NULL,
        FOREIGN KEY (creator_id) REFERENCES User(user_id)
    )
    """)
    
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS GroupMember (
        group_id TEXT NOT NULL,
        user_id TEXT NOT NULL,
        PRIMARY KEY (group_id, user_id),
        FOREIGN KEY (group_id) REFERENCES [Group](group_id),
        FOREIGN KEY (user_id) REFERENCES User(user_id)
    )
    """)
    
    conn.commit()
    conn.close()


# Initialize the database when starting the app
initialize_database()

# Helper functions
def verify_user_token(user_id: str, token: str) -> bool:
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    cursor.execute("SELECT token FROM User WHERE user_id = ?", (user_id,))
    result = cursor.fetchone()
    conn.close()
    return result is not None and  bcrypt.checkpw(token.encode(), result[0])

def is_user_in_group(user_id: str, group_id: str) -> bool:
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    cursor.execute(
        "SELECT 1 FROM GroupMember WHERE group_id = ? AND user_id = ?",
        (group_id, user_id)
    )
    result = cursor.fetchone() is not None
    conn.close()
    return result

def is_user_group_creator(user_id: str, group_id: str) -> bool:
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    cursor.execute(
        "SELECT creator_id FROM [Group] WHERE group_id = ?",
        (group_id,)
    )
    result = cursor.fetchone()
    conn.close()
    return result is not None and result[0] == user_id


async def _add_group_members(cursor, group_id: str, user_ids: List[str]):
    for user_id in user_ids:
        cursor.execute("SELECT 1 FROM User WHERE user_id = ?", (user_id,))
        if not cursor.fetchone():
            raise HTTPException(status_code=404, detail=f"User {user_id} not found")
        cursor.execute(
            "INSERT OR IGNORE INTO GroupMember (group_id, user_id) VALUES (?, ?)",
            (group_id, user_id)
        )


@app.get("/ping")
async def ping():
    return Response(content=str("Hello Messenger"), media_type="text/plain; charset=utf-8")


# User endpoints
@app.post("/register/")
async def register(user_id: str = Form(...), name: str = Form(...), public_key: str = Form(...)):
    user_id = user_id.lower()

    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    cursor.execute("SELECT 1 FROM User WHERE user_id = ?", (user_id,))
    if cursor.fetchone() is not None:
        conn.close()
        return {"status": 2, "detail": "Name in use"}
    
    token = os.urandom(32).hex()
    cursor.execute(
        "INSERT INTO User (user_id, name, token, public_key) VALUES (?, ?, ?, ?)",
        (user_id, name, bcrypt.hashpw(token.encode(), bcrypt.gensalt()), public_key)
    )
    conn.commit()
    conn.close()
    
    return {"status": 1, "token": token}


# Message endpoints
@app.post("/send/user/{recipient}/")
async def send_message(recipient: str, name: str = Form(...), token: str = Form(...),
                      message: str = Form(...)):
    name = name.lower()
    recipient = recipient.lower()
    
    if not verify_user_token(name, token):
        return {"status": 0, "detail": "Unauthorised"}
    
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    cursor.execute("SELECT 1 FROM User WHERE user_id = ?", (recipient,))
    if not cursor.fetchone():
        conn.close()
        return {"status": 2, "detail": "Recipient not found"}
    
    timestamp = int(time.time())
    
    cursor.execute(
        """INSERT INTO Message 
        (recipient_id, sender_id, channel_id, content, timestamp, is_group_channel)
        VALUES (?, ?, ?, ?, ?, ?)""",
        (recipient, name, name, message, timestamp, False)
    )
    message_id = cursor.lastrowid
    conn.commit()
    conn.close()
    
    return {"status": 1, "detail": "Sent", "message_id": message_id}


@app.post("/send/group/{group_id}/")
async def send_group_message(group_id: str, name: str = Form(...), token: str = Form(...), message: str = Form(...)):
    name = name.lower()
    group_id = group_id.lower()

    if not (verify_user_token(name, token) and is_user_in_group(name, group_id)):
        return {"status": 0, "detail": "Unauthorised"}

    timestamp = int(time.time())

    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()

    try:
        # Get all members in the group except the sender
        cursor.execute(
            "SELECT user_id FROM GroupMember WHERE group_id = ? AND user_id != ?",
            (group_id, name)
        )
        recipients = [row[0] for row in cursor.fetchall()]

        if not recipients:
            return {"status": 2, "detail": "No recipients found in group"}

        # Insert message for each recipient
        for recipient in recipients:
            cursor.execute(
                """INSERT INTO Message 
                (recipient_id, sender_id, channel_id, content, timestamp, is_group_channel)
                VALUES (?, ?, ?, ?, ?, ?)""",
                (recipient, name, group_id, message, timestamp, True)
            )

        conn.commit()
        return {
            "status": 1,
            "detail": f"Message sent to {len(recipients)} group members",
            "recipient_count": len(recipients)
        }

    except sqlite3.Error as e:
        conn.rollback()
        return {"status": 3, "detail": f"Database error: {str(e)}"}
    finally:
        conn.close()



@app.post("/acknowledge/")
async def acknowledge_messages(
    name: str = Form(...),
    token: str = Form(...)
):
    name = name.lower()
    
    if not verify_user_token(name, token):
        return {"status": 0, "detail": "Unauthorised"}
    
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    cursor.execute("SELECT COUNT(*) FROM Message WHERE recipient_id = ?", (name,))
    message_count = cursor.fetchone()[0]
    
    cursor.execute("DELETE FROM Message WHERE recipient_id = ?", (name,))
    deleted_count = cursor.rowcount
    conn.commit()
    conn.close()
    
    return {
        "status": 1,
        "detail": f"Deleted all {deleted_count} messages",
        "deleted_count": deleted_count,
        "previous_message_count": message_count
    }


@app.post("/get/")
async def get_messages(name: str = Form(...), token: str = Form(...)):
    name = name.lower()
    
    if not verify_user_token(name, token):
        return {"status": 0, "detail": "Unauthorised"}
    
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    cursor.execute(
        """SELECT sender_id, channel_id, content, timestamp, is_group_channel 
        FROM Message WHERE recipient_id = ? ORDER BY timestamp""",
        (name,)
    )
    
    messages = []
    for row in cursor.fetchall():
        messages.append({
            "sender": row[0],
            "channel": row[1],
            "content": row[2],
            "timestamp": row[3],
            "is_group": bool(row[4])
        })
    
    conn.close()
    return {"status": 1, "messages": messages}


# Group endpoints
@app.post("/group/create")
async def create_group(
    creator_id: str = Form(...),
    token: str = Form(...),
    group_name: str = Form(...),
    group_id: str = Form(...),
):
    creator_id = creator_id.lower()
    group_id = group_id.lower()
    
    if not verify_user_token(creator_id, token):
        raise HTTPException(status_code=401, detail="Unauthorized")

    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    try:
        cursor.execute(
            "INSERT INTO [Group] (group_id, name, creator_id) VALUES (?, ?, ?)",
            (group_id, group_name, creator_id)
        )
        
        await _add_group_members(cursor, group_id, [creator_id])
        
        conn.commit()
        return {
            "status": 1,
            "group_id": group_id,
            "group_name": group_name,
            "members": [creator_id]
        }
    except sqlite3.Error as e:
        conn.rollback()
        raise HTTPException(status_code=400, detail=f"Database error: {str(e)}")
    finally:
        conn.close()


@app.get("/group/{group_id}/")
async def get_group_details(group_id: str, name: str = Form(...), token: str = Form(...)):
    requester_id = requester_id.lower()
    group_id = group_id.lower()
    
    if not (verify_user_token(requester_id, token) and is_user_in_group(requester_id, group_id)):
        raise HTTPException(status=0, detail="Unauthorized")
    
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    try:
        cursor.execute("SELECT group_id, name, creator_id FROM [Group] WHERE group_id = ?", (group_id,))
        group_data = cursor.fetchone()
        if not group_data:
            raise HTTPException(status_code=3, detail="Group not found")

        cursor.execute(
            """SELECT u.user_id, u.public_key FROM GroupMember gm
            JOIN User u ON gm.user_id = u.user_id WHERE gm.group_id = ?""",
            (group_id,)
        )
        members = {
            row["user_id"]: row["public_key"] 
            for row in cursor.fetchall()
        }

        return {
            "status": 1,
            "group_id": group_data["group_id"],
            "name": group_data["name"],
            "creator_id": group_data["creator_id"],
            "members": members
        }

    except sqlite3.Error as e:
        raise HTTPException(status=2, detail=f"Database error")
    finally:
        conn.close()


@app.post("/group/{group_id}/add")
async def add_group_members(
    group_id: str,
    requester_id: str = Form(...),
    token: str = Form(...),
    new_member: str = Form(...)
):
    requester_id = requester_id.lower()
    group_id = group_id.lower()
    
    if not (verify_user_token(requester_id, token) and 
           is_user_in_group(requester_id, group_id)):
        raise HTTPException(status_code=401, detail="Unauthorized")
    
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    try:
        await _add_group_members(cursor, group_id, [new_member])
        conn.commit()
        return {
            "status": 1
        }
    except sqlite3.Error as e:
        conn.rollback()
        raise HTTPException(status_code=400, detail=f"Database error: {str(e)}")
    finally:
        conn.close()


@app.post("/group/{group_id}/remove/")
async def remove_group_member(
    group_id: str,
    member_id: str = Form(...),
    creator_id: str = Form(...),
    token: str = Form(...)
):
    creator_id = creator_id.lower()
    group_id = group_id.lower()
    member_id = member_id.lower()

    print(member_id)
    
    if not (verify_user_token(creator_id, token) and 
           is_user_group_creator(creator_id, group_id)):
        raise HTTPException(status_code=403, detail="Only group creator can remove members")
    
    if creator_id == member_id:
        raise HTTPException(status_code=400, detail="Use /leave endpoint to remove yourself")
    
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    try:
        cursor.execute(
            "DELETE FROM GroupMember WHERE group_id = ? AND user_id = ?",
            (group_id, member_id)
        )
        
        if cursor.rowcount == 0:
            raise HTTPException(status_code=404, detail="Member not found in group")
        
        cursor.execute(
            "SELECT COUNT(*) FROM GroupMember WHERE group_id = ?", 
            (group_id,)
        )
        remaining_members = cursor.fetchone()[0]
        
        conn.commit()
        return {
            "status": 1,
            "removed_member": member_id,
            "remaining_members": remaining_members
        }
    except sqlite3.Error as e:
        conn.rollback()
        raise HTTPException(status_code=400, detail=f"Database error: {str(e)}")
    finally:
        conn.close()


@app.post("/group/{group_id}/leave")
async def leave_group(
    group_id: str,
    user_id: str = Form(...),
    token: str = Form(...)
):
    user_id = user_id.lower()
    group_id = group_id.lower()
    
    if not verify_user_token(user_id, token):
        raise HTTPException(status_code=401, detail="Unauthorized")
    
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    try:
        cursor.execute(
            "DELETE FROM GroupMember WHERE group_id = ? AND user_id = ?",
            (group_id, user_id))
        
        cursor.execute(
            "SELECT COUNT(*) FROM GroupMember WHERE group_id = ?", 
            (group_id,))
        remaining_members = cursor.fetchone()[0]
        
        if remaining_members == 0:
            cursor.execute(
                "DELETE FROM [Group] WHERE group_id = ?", 
                (group_id,))
        
        conn.commit()
        return {
            "status": 1,
            "detail": "Left group" + (" and group was deleted" if remaining_members == 0 else "")
        }
    except sqlite3.Error as e:
        conn.rollback()
        raise HTTPException(status_code=400, detail=f"Database error: {str(e)}")
    finally:
        conn.close()


@app.post("/group/{group_id}/rename")
async def rename_group(
    group_id: str,
    new_name: str = Form(...),
    requester_id: str = Form(...),
    token: str = Form(...)
):
    requester_id = requester_id.lower()
    group_id = group_id.lower()
    
    if not (verify_user_token(requester_id, token) and 
           is_user_in_group(requester_id, group_id)):
        raise HTTPException(status_code=401, detail="Unauthorized")
    
    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()
    
    try:
        cursor.execute(
            "UPDATE [Group] SET name = ? WHERE group_id = ?",
            (new_name, group_id)
        )
        conn.commit()
        return {"status": 1, "new_name": new_name}
    except sqlite3.Error as e:
        conn.rollback()
        raise HTTPException(status_code=400, detail=f"Database error: {str(e)}")
    finally:
        conn.close()


@app.get("/user/{user_id}/")
async def get_user_details(
    user_id: str,
    requester_id: str = Query(...),
    token: str = Query(...)
):
    requester_id = requester_id.lower()
    user_id = user_id.lower()



    if not verify_user_token(requester_id, token):
        raise HTTPException(status_code=401, detail="Unauthorized")

    conn = sqlite3.connect(DATABASE_FILE)
    cursor = conn.cursor()

    try:
        # Get basic user info (excluding sensitive data like token)
        cursor.execute(
            "SELECT user_id, name, public_key FROM User WHERE user_id = ?",
            (user_id,)
        )
        user_data = cursor.fetchone()

        if not user_data:
            raise HTTPException(status_code=404, detail="User not found")

        return {
            "status": 1,
            "user_id": user_data[0],
            "name": user_data[1],
            "public_key": user_data[2]
        }

    except sqlite3.Error as e:
        raise HTTPException(status_code=500, detail=f"Database error: {str(e)}")
    finally:
        conn.close()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8069)